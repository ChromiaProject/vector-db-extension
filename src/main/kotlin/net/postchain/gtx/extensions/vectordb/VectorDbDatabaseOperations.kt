package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.base.data.DatabaseAccess
import net.postchain.common.exception.UserMistake
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseOperations.Companion.INDEX_PREFIX
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseOperations.Companion.VECTOR_DB_TABLE_STORED_VECTOR
import java.math.BigDecimal

class VectorDbDatabaseOperations {

    companion object : KLogging() {
        private const val TABLE_PREFIX: String = "sys.x."
        const val INDEX_PREFIX: String = "IDX_"

        const val VECTOR_DB_TABLE_STORED_VECTOR = "${TABLE_PREFIX}stored_vector"

        const val VECTOR_DB_COLUMN_CONTEXT = "context"
        const val VECTOR_DB_COLUMN_ID = "id"
        const val VECTOR_DB_COLUMN_EMBEDDING = "embedding"

        const val VECTOR_DB_INDEX_CONTEXT_ID = "context_id"
        const val VECTOR_DB_INDEX_EMBEDDING_HNSW = "embedding_hnsw_index" // Deprecated
    }

    private lateinit var vectorIndex: VectorDBIndex

    fun initialize(ctx: EContext, vectorDbConfig: VectorDbConfig) {

        logger.info { "Initializing vector db" }

        try {
            throw UserMistake("Invalid index type: ${vectorDbConfig.index}. Valid types are: ${VectorDBIndex.entries.joinToString(", ") { it.name }}")
        } catch (e: IllegalArgumentException) {
            throw UserMistake("Invalid index type: ${vectorDbConfig.index}")
        }

        DatabaseAccess.of(ctx).apply {

            // Create PG vector extension in this schema
            ctx.conn.createStatement()
                    .execute("CREATE EXTENSION IF NOT EXISTS vector")

            removeLegacyStructure(ctx)
            val tableName = getVectorDbTableName(ctx)

            // Create the vector table
            ctx.conn.createStatement()
                    .execute("""
                        CREATE TABLE IF NOT EXISTS $tableName ($VECTOR_DB_COLUMN_CONTEXT bigint,$VECTOR_DB_COLUMN_ID bigint,
                            $VECTOR_DB_COLUMN_EMBEDDING halfvec(${vectorDbConfig.dimensions}))
                        """.trimIndent())

            // Context id index
            val contextIdIndexName = getVectorDbTableIndexName(ctx, VECTOR_DB_INDEX_CONTEXT_ID)
            ctx.conn.createStatement().execute("""
                CREATE INDEX IF NOT EXISTS "$contextIdIndexName" on $tableName ("$VECTOR_DB_COLUMN_CONTEXT", "$VECTOR_DB_COLUMN_ID")
                """.trimIndent()
            )

            // Create embedding index
            val embeddedHnswIndexName = getVectorDbTableIndexName(ctx, vectorIndex.indexName)

            // Make sure we don't add a new index type - if we want to support this we need to expand the query part
            // to provide the distance query operator for each index
            val embeddingIndexList = VectorDBIndex.entries.map { getVectorDbTableIndexName(ctx, it.indexName) }
            val tableEmbeddingIndexes = getTableIndexes(ctx, tableName)
                    .filter { embeddingIndexList.contains(it) }
            if (tableEmbeddingIndexes.any { it != embeddedHnswIndexName }) {
                throw UserMistake("Changing embedded index is not supported")
            } else {

                logger.info { "Creating embedding index of type ${vectorIndex.indexEmbedding}" }

                ctx.conn.createStatement().execute(
                        """
                        CREATE INDEX IF NOT EXISTS "$embeddedHnswIndexName"
                        ON $tableName USING hnsw ($VECTOR_DB_COLUMN_EMBEDDING ${vectorIndex.indexEmbedding})
                        """.trimIndent()
                )
            }
        }
    }

    private fun getTableIndexes(ctx: EContext, tableName: String): Set<String> {
        val results = mutableSetOf<String>()
        val resultSet = ctx.conn.metaData.getIndexInfo(null, null, tableName.replace("\"", ""), false, false)
        while (resultSet.next()) {
            val indexName = resultSet.getString("INDEX_NAME")
            results.add(indexName)
        }

        return results
    }

    private fun DatabaseAccess.removeLegacyStructure(ctx: EContext) {

        // One of the first index names, most likely not in use anywhere
        val embeddedHnswIndexName = getVectorDbTableIndexName(ctx, VECTOR_DB_INDEX_EMBEDDING_HNSW)
        ctx.conn.createStatement().execute("""
                    DROP INDEX IF EXISTS "$embeddedHnswIndexName"
                    """.trimIndent()
        )
    }

    fun storeVectors(ctx: TxEContext, context: Long, vectors: List<Pair<String, Long>>, batchSize: Long = 300) {
        DatabaseAccess.of(ctx).apply {
            val tableName = getVectorDbTableName(ctx)
            ctx.conn.prepareStatement("""
                INSERT INTO $tableName ($VECTOR_DB_COLUMN_CONTEXT, $VECTOR_DB_COLUMN_ID, $VECTOR_DB_COLUMN_EMBEDDING) VALUES (?, ?, ?::vector)
                """.trimIndent()
            ).use { stmt ->
                vectors.forEachIndexed { index, data ->
                    stmt.setLong(1, context)
                    stmt.setLong(2, data.second)
                    stmt.setString(3, data.first)
                    stmt.addBatch()

                    if (index % batchSize == 0L) {
                        stmt.executeBatch()
                    }
                }
                stmt.executeBatch()
            }
        }
    }

    fun deleteVectors(ctx: TxEContext, context: Long, ids: List<Long>) {
        DatabaseAccess.of(ctx).apply {
            val tableName = getVectorDbTableName(ctx)
            var rowsAffected = 0
            ids.forEach {
                ctx.conn.prepareStatement("""
                    DELETE FROM $tableName WHERE $VECTOR_DB_COLUMN_CONTEXT = ? AND $VECTOR_DB_COLUMN_ID = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setLong(1, context)
                    stmt.setLong(2, it)
                    rowsAffected += stmt.executeUpdate()
                }
            }
            logger.info { "Deleted $rowsAffected vectors" }
        }
    }

    fun queryClosestObjects(ctx: EContext, context: Long, vectorQuery: String, maxDistance: BigDecimal, maxVectors: Long): GtvArray {
        DatabaseAccess.of(ctx).apply {
            val tableName = getVectorDbTableName(ctx)
            ctx.conn.prepareStatement(
                    """
                    WITH nearest_results AS MATERIALIZED (
                        SELECT $VECTOR_DB_COLUMN_ID, $VECTOR_DB_COLUMN_EMBEDDING ${vectorIndex.operator} ?::halfvec AS distance 
                        FROM $tableName
                        WHERE $VECTOR_DB_COLUMN_CONTEXT = ? ORDER BY distance
                        LIMIT ?
                    ) SELECT $VECTOR_DB_COLUMN_ID, distance FROM nearest_results WHERE distance <= ? ORDER BY distance
                    """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, vectorQuery)
                stmt.setLong(2, context)
                stmt.setLong(3, maxVectors)
                stmt.setBigDecimal(4, maxDistance)
                val rs = stmt.executeQuery()

                val result = mutableListOf<Gtv>()
                while (rs.next()) {
                    result.add(gtv(
                            "id" to gtv(rs.getLong(1)),
                            "distance" to gtv(rs.getString(2))
                    ))
                }
                return gtv(result)
            }
        }
    }
}

fun DatabaseAccess.getVectorDbTableName(ctx: EContext): String {
    return tableName(ctx, VECTOR_DB_TABLE_STORED_VECTOR)
}

fun DatabaseAccess.getVectorDbTableIndexName(ctx: EContext, name: String): String {
    val tableName = getVectorDbTableName(ctx)
            .replace("\"", "")
    return "$INDEX_PREFIX${tableName}_$name"
}
