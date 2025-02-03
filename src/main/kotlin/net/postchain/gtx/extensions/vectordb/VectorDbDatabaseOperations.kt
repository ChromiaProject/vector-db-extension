package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.base.data.DatabaseAccess
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvFactory.gtv
import java.math.BigDecimal

class VectorDbDatabaseOperations {

    companion object : KLogging() {
        private const val TABLE_PREFIX: String = "sys.x_vector_" // This name should not clash with Rell
        private const val INDEX_PREFIX: String = "IDX_"

        const val VECTOR_DB_TABLE_STORED_VECTOR = "stored_vector"

        const val VECTOR_DB_COLUMN_CONTEXT = "context"
        const val VECTOR_DB_COLUMN_ID = "id"
        const val VECTOR_DB_COLUMN_EMBEDDING = "embedding"

        const val VECTOR_DB_INDEX_CONTEXT_ID = "context_id"
        const val VECTOR_DB_INDEX_EMBEDDING_HNSW = "embedding_hnsw_index"

        fun getChainTableName(chainId: Long, name: String): String {
            return "$TABLE_PREFIX${chainId}_$name"
        }

        fun getChainIndexName(chainId: Long, name: String): String {
            return "$INDEX_PREFIX${chainId}_$name"
        }
    }

    fun initialize(ctx: EContext, vectorDbConfig: VectorDbConfig) {

        DatabaseAccess.of(ctx).apply {

            ctx.conn.createStatement()
                    .execute("CREATE EXTENSION IF NOT EXISTS vector")

            val tableName = getChainTableName(ctx.chainID, VECTOR_DB_TABLE_STORED_VECTOR)

            // halfvec is half the size of vector
            ctx.conn.createStatement()
                    .execute("""
                        CREATE TABLE IF NOT EXISTS "$tableName" ($VECTOR_DB_COLUMN_CONTEXT bigint, $VECTOR_DB_COLUMN_ID bigint, $VECTOR_DB_COLUMN_EMBEDDING halfvec(${vectorDbConfig.dimensions}))
                        """.trimIndent())

            val contextIdIndexName = getChainIndexName(ctx.chainID, VECTOR_DB_INDEX_CONTEXT_ID)
            ctx.conn.createStatement().execute("""
                CREATE INDEX IF NOT EXISTS "$contextIdIndexName" on "$tableName"("$VECTOR_DB_COLUMN_CONTEXT", "$VECTOR_DB_COLUMN_ID")
                """.trimIndent()
            )

            val embeddedHnswIndexName = getChainIndexName(ctx.chainID, VECTOR_DB_INDEX_EMBEDDING_HNSW)
            ctx.conn.createStatement().execute("""
                CREATE INDEX IF NOT EXISTS "$embeddedHnswIndexName"
                ON "$tableName" USING hnsw (($VECTOR_DB_COLUMN_EMBEDDING::halfvec(${vectorDbConfig.dimensions})) halfvec_l2_ops)
                """.trimIndent()
            )
            //                ON "$tableName" USING hnsw (embedding vector_cosine_ops) -- full precision
        }
    }

    fun storeVector(ctx: TxEContext, id: Long, context: Long, vector: String) {
        DatabaseAccess.of(ctx).apply {
            val tableName = getChainTableName(ctx.chainID, VECTOR_DB_TABLE_STORED_VECTOR)
            ctx.conn.prepareStatement("""
                    INSERT INTO "$tableName" ($VECTOR_DB_COLUMN_CONTEXT, $VECTOR_DB_COLUMN_ID, $VECTOR_DB_COLUMN_EMBEDDING) VALUES (?, ?, ?::vector)
                    """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, context)
                stmt.setLong(2, id)
                stmt.setString(3, vector)
                stmt.execute()
            }
        }
    }

    fun deleteVector(ctx: TxEContext, id: Long, context: Long) {
        DatabaseAccess.of(ctx).apply {
            val tableName = getChainTableName(ctx.chainID, VECTOR_DB_TABLE_STORED_VECTOR)
            ctx.conn.prepareStatement("""
                    DELETE FROM "$tableName" WHERE $VECTOR_DB_COLUMN_CONTEXT = ? AND $VECTOR_DB_COLUMN_ID = ?
                    """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, context)
                stmt.setLong(2, id)
                val rowsAffected = stmt.executeUpdate()
                logger.info { "Deleted $rowsAffected vectors" }
            }
        }
    }

    fun queryClosestObjects(ctx: EContext, context: Long, vectorQuery: String, maxDistance: BigDecimal, maxVectors: Long): GtvArray {
        DatabaseAccess.of(ctx).apply {
            val tableName = getChainTableName(ctx.chainID, VECTOR_DB_TABLE_STORED_VECTOR)
            ctx.conn.prepareStatement(
                    """
                    WITH nearest_results AS MATERIALIZED (
                        SELECT $VECTOR_DB_COLUMN_ID, $VECTOR_DB_COLUMN_EMBEDDING <=> ?::vector AS distance 
                        FROM "$tableName" 
                        WHERE $VECTOR_DB_COLUMN_CONTEXT = ? ORDER BY distance
                        LIMIT ?
                    ) SELECT $VECTOR_DB_COLUMN_ID, distance FROM nearest_results WHERE distance <= ? ORDER BY distance;
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