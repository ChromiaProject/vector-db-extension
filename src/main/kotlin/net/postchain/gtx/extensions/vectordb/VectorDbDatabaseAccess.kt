package net.postchain.gtx.extensions.vectordb

import com.google.common.util.concurrent.ThreadFactoryBuilder
import mu.KLogging
import net.postchain.base.data.DatabaseAccess
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig
import net.postchain.gtx.extensions.vectordb.lib.vector_db_query_compute.QueryResultObject
import org.postgresql.PGConnection
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.Long

class VectorDbDatabaseAccess{

    companion object : KLogging() {
        const val PG_VECTOR_SCHEMA: String = "public"
        private const val TABLE_PREFIX: String = "sys.x.vectordb."

        const val TABLE_COLLECTION_META = "${TABLE_PREFIX}collection_meta"
        const val TABLE_COLLECTION = "${TABLE_PREFIX}collection_"
        const val REUSABLE_DATUM_ID = "${TABLE_PREFIX}reusable_datum_id"

        /** The ID of a collection, should never be re-used after collection is removed */
        const val COLLECTION_META_COLUMN_ID = "id"
        const val COLLECTION_META_COLUMN_NAME = "name"
        const val COLLECTION_META_COLUMN_DIMENSIONS = "dimensions"
        const val COLLECTION_META_COLUMN_INDEX_TYPE = "index_type"
        const val COLLECTION_META_COLUMN_QUERY_MAX_VECTORS = "query_max_vectors"
        const val COLLECTION_META_COLUMN_STORE_BATCH_SIZE = "store_batch_size"
        const val COLLECTION_META_COLUMN_ORIGIN = "origin"
        const val COLLECTION_META_COLUMN_EXISTS = "exists"

        const val COLLECTION_COLUMN_DATUM_ID = "datum_id"
        const val COLLECTION_COLUMN_CONTEXT = "context"
        const val COLLECTION_COLUMN_ID = "id"
        const val COLLECTION_COLUMN_EMBEDDING = "embedding"
        const val COLLECTION_COLUMN_EXCLUDE = "exclude"

        const val REUSABLE_DATUM_ID_COLUMN_DATUM_ID = "datum_id"

        private val timeouter: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
                ThreadFactoryBuilder().setNameFormat("VDB-timeout").setDaemon(true).build()
        )

        fun <T> withTimeout(ctx: EContext, timeout: Duration, block: () -> T): T {
            val queryTimeoutMs = timeout.toMillis()
            val timedOut = AtomicBoolean(false)
            val timeoutTask = if (queryTimeoutMs > 0) {
                val opThread = Thread.currentThread()
                timeouter.schedule({
                    logger.warn("Query timed out after $queryTimeoutMs ms, attempting to cancel")

                    timedOut.set(true)
                    opThread.interrupt()

                    if (ctx.conn.isWrapperFor(PGConnection::class.java)) {
                        val postgresConnection = ctx.conn.unwrap(PGConnection::class.java)
                        try {
                            postgresConnection.cancelQuery()
                        } catch (e: SQLException) {
                            logger.warn { "Failed to cancel query on chain ${ctx.chainID}: $e" }
                        }
                    }
                }, queryTimeoutMs, TimeUnit.MILLISECONDS)
            } else null

            val db = DatabaseAccess.of(ctx)
            db.setLocalLockTimeout(ctx, queryTimeoutMs)

            try {
                return block()
            } finally {
                timeoutTask?.cancel(false)
                try {
                    db.resetLocalLockTimeout(ctx)
                } catch (e: SQLException) {
                    logger.error { "Failed to reset local lock timeout: $e" }
                }
                if (timedOut.get()) {
                    logger.info { "Query timed out after $queryTimeoutMs ms" }
                    throw TimeoutException("Query timed out after $queryTimeoutMs ms")
                }
            }
        }
    }

    fun initialize(ctx: EContext) {

        logger.info { "Initializing vector db" }

        dropDatumIdSeqTable(ctx)
        initializeCollectionsTable(ctx)
        initializeReusableDatumIdTable(ctx)
    }

    fun createOrUpdateCollectionTables(ctx: EContext) {
        getExistingCollections(ctx).values.forEach { collection ->
            createOrUpdateTable(ctx, collection)}
    }

    fun getCollectionOrigins(ctx: EContext): Set<VectorCollectionOrigin> {
        return getCollections(ctx).map { it.origin }.toSet()
    }

    fun wipeVectorDb(ctx: EContext) {
        listOf(
                getCollectionMetaTableName(ctx),
                getReusableDatumIdTableName(ctx)
        ) + getCollections(ctx).map { getCollectionTableName(ctx, it.id) }
                .forEach {
                    logger.debug { "Dropping table $it" }
                    ctx.conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS $it CASCADE") }
                }
    }

    fun initializeCollectionsTable(ctx: EContext) {
        val collectionMetaTableName = getCollectionMetaTableName(ctx)
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS $collectionMetaTableName (
                        $COLLECTION_META_COLUMN_ID bigint NOT NULL PRIMARY KEY,
                        $COLLECTION_META_COLUMN_NAME text NOT NULL UNIQUE,
                        $COLLECTION_META_COLUMN_ORIGIN text NOT NULL,
                        $COLLECTION_META_COLUMN_DIMENSIONS bigint NOT NULL,
                        $COLLECTION_META_COLUMN_INDEX_TYPE text NOT NULL,
                        $COLLECTION_META_COLUMN_QUERY_MAX_VECTORS bigint NOT NULL,
                        $COLLECTION_META_COLUMN_STORE_BATCH_SIZE bigint NOT NULL
                        )
                        """)

            stmt.execute("""
                    ALTER TABLE $collectionMetaTableName 
                    ADD COLUMN IF NOT EXISTS $COLLECTION_META_COLUMN_EXISTS boolean NOT NULL DEFAULT true
                """)
        }
    }

    fun initializeReusableDatumIdTable(ctx: EContext) {
        val tableName = getReusableDatumIdTableName(ctx)
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE IF NOT EXISTS $tableName ($REUSABLE_DATUM_ID_COLUMN_DATUM_ID bigint NOT NULL PRIMARY KEY)")
        }
    }

    fun storeCollections(ctx: EContext, collections: List<VectorCollection>) {
        ctx.conn.prepareStatement("""
            INSERT INTO ${getCollectionMetaTableName(ctx)}
            ($COLLECTION_META_COLUMN_ID, $COLLECTION_META_COLUMN_NAME, $COLLECTION_META_COLUMN_ORIGIN,
            $COLLECTION_META_COLUMN_DIMENSIONS, $COLLECTION_META_COLUMN_INDEX_TYPE,
            $COLLECTION_META_COLUMN_QUERY_MAX_VECTORS, $COLLECTION_META_COLUMN_STORE_BATCH_SIZE,
            $COLLECTION_META_COLUMN_EXISTS)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ($COLLECTION_META_COLUMN_ID) DO UPDATE SET
            $COLLECTION_META_COLUMN_QUERY_MAX_VECTORS = EXCLUDED.$COLLECTION_META_COLUMN_QUERY_MAX_VECTORS,
            $COLLECTION_META_COLUMN_STORE_BATCH_SIZE = EXCLUDED.$COLLECTION_META_COLUMN_STORE_BATCH_SIZE
            """
        ).use { stmt ->
            collections.forEach { collection ->
                stmt.setLong(1, collection.id)
                stmt.setString(2, collection.name)
                stmt.setString(3, collection.origin.name)
                stmt.setLong(4, collection.dimensions)
                stmt.setString(5, collection.index.name)
                stmt.setLong(6, collection.queryMaxVectors)
                stmt.setLong(7, collection.storeBatchSize)
                stmt.setBoolean(8, collection.exists)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    fun createCollection(ctx: EContext, collectionName: String, config: VectorDbCollectionConfig): VectorCollection {
        val id = getNextTableId(ctx)
        val collection = VectorCollection(id, collectionName, config, VectorCollectionOrigin.DYNAMIC)
        createOrUpdateTable(ctx, collection)

        storeCollections(ctx, listOf(collection))
        return collection
    }

    fun deleteCollection(ctx: EContext, collection: VectorCollection) {
        val collectionTableName = getCollectionTableName(ctx, collection.id)

        // Mark datum ids reusable
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                INSERT INTO ${getReusableDatumIdTableName(ctx)} ($REUSABLE_DATUM_ID_COLUMN_DATUM_ID)
                SELECT $COLLECTION_COLUMN_DATUM_ID
                FROM $collectionTableName
            """)

            stmt.execute("UPDATE ${getCollectionMetaTableName(ctx)} SET $COLLECTION_META_COLUMN_EXISTS = false WHERE $COLLECTION_META_COLUMN_ID = ${collection.id}")

            stmt.execute("DROP TABLE IF EXISTS $collectionTableName CASCADE")
        }
        deleteCollectionIdEntry(ctx, collection)
    }

    fun updateCollection(ctxt: TxEContext, collection: VectorCollection, queryMaxVectors: Long?, storeBatchSize: Long?): VectorCollection {
        val tableName = getCollectionMetaTableName(ctxt)
        val updatedQueryMaxVectors = queryMaxVectors ?: collection.queryMaxVectors
        val updatedStoreBatchSize = storeBatchSize ?: collection.storeBatchSize

        ctxt.conn.createStatement().use { stmt ->
            stmt.execute("UPDATE $tableName SET $COLLECTION_META_COLUMN_QUERY_MAX_VECTORS = $updatedQueryMaxVectors, $COLLECTION_META_COLUMN_STORE_BATCH_SIZE = $updatedStoreBatchSize WHERE $COLLECTION_META_COLUMN_ID = ${collection.id}")
        }
        return collection.copy(queryMaxVectors = updatedQueryMaxVectors, storeBatchSize = updatedStoreBatchSize)
    }

    private fun deleteCollectionIdEntry(ctx: EContext, collection: VectorCollection) {
        val tableName = getCollectionMetaTableName(ctx)
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("DELETE FROM $tableName WHERE $COLLECTION_META_COLUMN_ID = ${collection.id}")
        }
    }

    fun getNextTableId(ctx: EContext): Long {
        return ctx.conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT MAX(id) AS max_value FROM ${getCollectionMetaTableName(ctx)}").use {
                if (it.next()) {
                    val maxValue = it.getLong("max_value")
                    if (it.wasNull()) {
                        0L
                    } else {
                        maxValue + 1
                    }
                } else {
                    0L
                }
            }
        }
    }

    private fun createOrUpdateTable(ctx: EContext, collection: VectorCollection) {

        val tableName = getCollectionTableName(ctx, collection.id)

        // Create the vector table
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS $tableName (
                        $COLLECTION_COLUMN_DATUM_ID bigint NOT NULL PRIMARY KEY,
                        $COLLECTION_COLUMN_CONTEXT bigint NOT NULL,
                        $COLLECTION_COLUMN_ID bigint NOT NULL,
                        $COLLECTION_COLUMN_EMBEDDING ${PG_VECTOR_SCHEMA}.halfvec(${collection.dimensions}) NOT NULL)
                        """)
        }

        // Drop datum_id_key index (replaced by pkey)
        val datumIdIndexName = getTableIndexName(tableName, "datum_id_key")
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""DROP INDEX IF EXISTS "$datumIdIndexName"""")
        }

        // Context index
        val contextIndexName = getTableIndexName(tableName, "context")
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""CREATE INDEX IF NOT EXISTS "$contextIndexName" ON $tableName ("$COLLECTION_COLUMN_CONTEXT")""")
        }

        // Context & id index
        val contextAndIdIndexName = getTableIndexName(tableName, "context_and_id")
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS "$contextAndIdIndexName" ON $tableName ("$COLLECTION_COLUMN_CONTEXT", "$COLLECTION_COLUMN_ID")
                """
            )
        }

        // Create embedding index
        val embeddedHnswIndexName = getTableIndexName(tableName, collection.index.indexName)

        ctx.conn.createStatement().use { stmt ->
            stmt.execute(
                    """
                CREATE INDEX IF NOT EXISTS "$embeddedHnswIndexName"
                ON $tableName USING hnsw ($COLLECTION_COLUMN_EMBEDDING ${PG_VECTOR_SCHEMA}.${collection.index.indexEmbedding})
                """
            )
        }

        // Add column for vector exclusion. This works as a soft remove to exclude vectors from query, before they are removed
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                ALTER TABLE $tableName
                ADD COLUMN IF NOT EXISTS $COLLECTION_COLUMN_EXCLUDE BOOLEAN DEFAULT false                
            """)
        }

        // Index for exclude
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS "${getTableIndexName(tableName, COLLECTION_COLUMN_EXCLUDE)}"
                ON $tableName ($COLLECTION_COLUMN_EXCLUDE)
            """)
        }
    }

    private fun getTableIndexName(tableName: String, suffix: String): String {
        val cleanTableName = tableName.replace("\"", "")
        return "${cleanTableName}_$suffix"
    }

    fun storeVectors(ctx: EContext, tableId: Long, vectors: List<Vector>, batchSize: Long = 300) {
        val tableName = getCollectionTableName(ctx, tableId)
        ctx.conn.prepareStatement("""
            INSERT INTO $tableName ($COLLECTION_COLUMN_DATUM_ID, $COLLECTION_COLUMN_CONTEXT, $COLLECTION_COLUMN_ID, $COLLECTION_COLUMN_EMBEDDING, $COLLECTION_COLUMN_EXCLUDE)
            VALUES (?, ?, ?, ?::${PG_VECTOR_SCHEMA}.halfvec, ?)
            """
        ).use { stmt ->
            vectors.forEachIndexed { index, vector ->
                stmt.setLong(1, vector.datumId)
                stmt.setLong(2, vector.context)
                stmt.setLong(3, vector.refId)
                stmt.setString(4, vector.vector)
                stmt.setBoolean(5, vector.exclude)
                stmt.addBatch()

                if ((index + 1) % batchSize == 0L) {
                    if (stmt.executeBatch().any { it == Statement.EXECUTE_FAILED }) {
                        throw ProgrammerMistake("Failed to store vectors")
                    }
                }
            }

            if (vectors.size % batchSize != 0L) {
                if (stmt.executeBatch().any { it == Statement.EXECUTE_FAILED }) {
                    throw ProgrammerMistake("Failed to store vectors")
                }
            }
        }

        // Delete if reused datum id(s)
        ctx.conn.prepareStatement("""
            DELETE FROM ${getReusableDatumIdTableName(ctx)}
            WHERE $REUSABLE_DATUM_ID_COLUMN_DATUM_ID = ANY(?)
        """).use { stmt ->
            stmt.setArray(1, ctx.conn.createArrayOf("bigint", vectors.map { it.datumId }.toTypedArray()))
            stmt.execute()
        }
    }

    fun excludeVectors(ctx: EContext, tableId: Long, context: Long, ids: Set<Long>) {
        val rowsAffected = ctx.conn.prepareStatement("""
                UPDATE ${getCollectionTableName(ctx, tableId)}
                SET $COLLECTION_COLUMN_EXCLUDE = true
                 WHERE $COLLECTION_COLUMN_CONTEXT = ? AND $COLLECTION_COLUMN_ID = ANY(?)
             """).use { stmt ->
            stmt.setLong(1, context)
            stmt.setArray(2, ctx.conn.createArrayOf("bigint", ids.toTypedArray()))
            stmt.executeUpdate()
        }

        logger.info { "Excluded $rowsAffected vectors" }
    }

    fun deleteVectors(ctx: EContext, tableId: Long, context: Long, ids: Set<Long>) {

        // Mark datum ids reusable
        ctx.conn.prepareStatement("""
            INSERT INTO ${getReusableDatumIdTableName(ctx)} ($REUSABLE_DATUM_ID_COLUMN_DATUM_ID)
            SELECT $COLLECTION_COLUMN_DATUM_ID
            FROM ${getCollectionTableName(ctx, tableId)}
            WHERE $COLLECTION_COLUMN_CONTEXT = ? AND $COLLECTION_COLUMN_ID = ANY(?)
        """).use { stmt ->
            stmt.setLong(1, context)
            stmt.setArray(2, ctx.conn.createArrayOf("bigint", ids.toTypedArray()))
            stmt.execute()
        }

        // Remove vectors
        val rowsAffected = ctx.conn.prepareStatement(
                "DELETE FROM ${getCollectionTableName(ctx, tableId)} WHERE $COLLECTION_COLUMN_CONTEXT = ? AND $COLLECTION_COLUMN_ID = ANY(?)"
        ).use { stmt ->
            stmt.setLong(1, context)
            stmt.setArray(2, ctx.conn.createArrayOf("bigint", ids.toTypedArray()))
            stmt.executeUpdate()
        }

        logger.info { "Deleted $rowsAffected vectors" }
    }

    fun queryClosestObjects(
            ctx: EContext, tableId: Long, context: Long?, vectorQuery: String, maxDistance: BigDecimal,
            maxVectors: Long, index: VectorDBIndex
    ): List<QueryResultObject> {
        val tableName = getCollectionTableName(ctx, tableId)
        var ai = 1
        val vectorRsIdx = ai++
        val contextRsIdx = if (context != null) ai++ else -1
        val maxVectorsRsIdx = ai++
        val maxDistanceRsIdx = ai
        ctx.conn.prepareStatement(
                """
                WITH nearest_results AS MATERIALIZED (
                    SELECT $COLLECTION_COLUMN_CONTEXT, $COLLECTION_COLUMN_ID, $COLLECTION_COLUMN_EMBEDDING OPERATOR("$PG_VECTOR_SCHEMA".${index.operator}) ?::${PG_VECTOR_SCHEMA}.halfvec AS distance
                    FROM $tableName
                    WHERE $COLLECTION_COLUMN_EXCLUDE = false
                    ${if (context == null) "" else "AND $COLLECTION_COLUMN_CONTEXT = ?"}
                    ORDER BY distance
                    LIMIT ?
                ) SELECT $COLLECTION_COLUMN_CONTEXT, $COLLECTION_COLUMN_ID, distance FROM nearest_results WHERE distance <= ? ORDER BY distance
                """
        ).use { stmt ->
            with(stmt) {
                setString(vectorRsIdx, vectorQuery)
                if (context != null) {
                    setLong(contextRsIdx, context)
                }
                setLong(maxVectorsRsIdx, maxVectors)
                setBigDecimal(maxDistanceRsIdx, maxDistance)
            }

            val result = mutableListOf<QueryResultObject>()
            stmt.executeQuery().use {
                while (it.next()) {
                    result.add(QueryResultObject(it.getLong(2), it.getLong(1), it.getString(3)))
                }
            }
            return result.toList()
        }
    }

    fun getDistanceOfResults(ctx: EContext, collection: VectorCollection, vector: String, result: List<QueryResultObject>): List<QueryResultObject> {
        val ids = result.map { it.id }.toSet()
        val contexts = result.map { it.context }.toSet()
        ctx.conn.prepareStatement("""
                SELECT $COLLECTION_COLUMN_ID, $COLLECTION_COLUMN_CONTEXT, $COLLECTION_COLUMN_EMBEDDING OPERATOR("$PG_VECTOR_SCHEMA".${collection.index.operator}) ?::${PG_VECTOR_SCHEMA}.halfvec AS distance
                FROM ${getCollectionTableName(ctx, collection.id)}
                WHERE id = ANY(?) AND context = ANY(?)
                ORDER BY distance
            """).use { stmt ->
            stmt.setString(1, vector)
            stmt.setArray(2, ctx.conn.createArrayOf("bigint", ids.toTypedArray()))
            stmt.setArray(3, ctx.conn.createArrayOf("bigint", contexts.toTypedArray()))
            stmt.executeQuery().use { rs ->
                val localResult = mutableListOf<QueryResultObject>()
                while (rs.next()) {
                    localResult.add(QueryResultObject(rs.getLong(1), rs.getLong(2), rs.getString(3)))
                }
                return localResult.toList()
            }
        }
    }

    fun getVectorsFromContextIds(ctx: EContext, tableId: Long, context: Long, ids: Set<Long>): Set<Vector> {
        val tableName = getCollectionTableName(ctx, tableId)
        val vectors = mutableSetOf<Vector>()
        ctx.conn.prepareStatement("""
                SELECT $COLLECTION_COLUMN_DATUM_ID, $COLLECTION_COLUMN_ID, $COLLECTION_COLUMN_EMBEDDING, $COLLECTION_COLUMN_EXCLUDE
                FROM $tableName
                WHERE $COLLECTION_COLUMN_CONTEXT = ? AND $COLLECTION_COLUMN_ID = ANY(?)
        """).use { stmt ->
            stmt.setLong(1, context)
            stmt.setArray(2, ctx.conn.createArrayOf("bigint", ids.toTypedArray()))

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    vectors.add(Vector(rs.getLong(1), context,
                            rs.getLong(2), rs.getString(3), rs.getBoolean(4)))
                }
            }
        }
        return vectors
    }

    fun getCollectionMetaTableName(ctx: EContext): String {
        return tableName(ctx, TABLE_COLLECTION_META)
    }

    fun getReusableDatumIdTableName(ctx: EContext): String {
        return tableName(ctx, REUSABLE_DATUM_ID)
    }

    fun getCollectionTableName(ctx: EContext, id: Long): String {
        return tableName(ctx, "${TABLE_COLLECTION}${id}")
    }

    fun addReusableDatumIds(ctxt: EContext, datumIds: Set<Long>) {
        if (datumIds.isNotEmpty()) {
            ctxt.conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO ${getReusableDatumIdTableName(ctxt)} ($REUSABLE_DATUM_ID_COLUMN_DATUM_ID) VALUES ${datumIds.joinToString("),(", "(", ")")}")
            }
        }
    }

    fun getNextAvailableDatumIdFromSequence(ctx: EContext): Long {
        val collectionMaxSql = getExistingCollections(ctx).values
                .map { "SELECT MAX(datum_id) AS max_value FROM ${getCollectionTableName(ctx, it.id)}" } +
                listOf("SELECT MAX(datum_id) AS max_value FROM ${getReusableDatumIdTableName(ctx)}")
        val sql = """
            SELECT MAX(max_value) FROM (
                ${collectionMaxSql.joinToString(" UNION ALL ")}
            ) AS combined_maxes;
        """.trimIndent()

        ctx.conn.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs ->
            if (rs.next()) {
                val maxValue = rs.getLong(1)
                if (!rs.wasNull()) {
                    return maxValue + 1
                }
            }
        }}
        return VectorDbGTXModule.VECTOR_DB_META_DATUM_ID + 1
    }

    fun getAvailableDatumIds(ctx: EContext, count: Int): LinkedList<Long> {
        val availableIds = LinkedList<Long>()
        ctx.conn.createStatement().use { stmt -> stmt.executeQuery("""
            SELECT $REUSABLE_DATUM_ID_COLUMN_DATUM_ID FROM ${getReusableDatumIdTableName(ctx)}
            ORDER BY $REUSABLE_DATUM_ID_COLUMN_DATUM_ID
            LIMIT $count
            """).use { rs ->
            while (rs.next()) {
                availableIds.add(rs.getLong(1))
            }
        }}

        if (availableIds.size < count) {
            val seqOffset = getNextAvailableDatumIdFromSequence(ctx)
            (seqOffset..<(seqOffset + count - availableIds.size)).forEach { availableIds.add(it) }
        }

        return availableIds
    }

    fun getExistingCollections(ctx: EContext): Map<String, VectorCollection> {
        return getCollections(ctx, existing = true).associateBy { it.name }
    }

    fun getCollections(ctx: EContext, name: String? = null, existing: Boolean? = null): MutableList<VectorCollection> {
        val tableName = getCollectionMetaTableName(ctx)
        val collections = mutableListOf<VectorCollection>()
        ctx.conn.createStatement().use { stmt ->
            stmt.executeQuery("""
            SELECT $COLLECTION_META_COLUMN_ID,
                $COLLECTION_META_COLUMN_NAME, $COLLECTION_META_COLUMN_ORIGIN, $COLLECTION_META_COLUMN_DIMENSIONS,
                $COLLECTION_META_COLUMN_INDEX_TYPE, $COLLECTION_META_COLUMN_QUERY_MAX_VECTORS,
                $COLLECTION_META_COLUMN_STORE_BATCH_SIZE, $COLLECTION_META_COLUMN_EXISTS FROM $tableName
                """).use { rs ->
                while (rs.next()) {
                    val collection = parseCollectionMetaRow(rs)
                    if (
                            (name == null || collection.name == name) &&
                            (existing == null || collection.exists == existing)) {
                        collections.add(collection)
                    }
                }
            }
        }
        return collections
    }

    fun getExistingCollectionByName(ctx: EContext, collectionName: String): VectorCollection? {
        val collections = getCollections(ctx, collectionName, existing = true)
        return when {
            collections.isEmpty() -> null
            collections.size == 1 -> collections[0]
            else -> throw ProgrammerMistake("Multiple collections with name '$collectionName' exist")
        }
    }

    private fun parseCollectionMetaRow(rs: ResultSet): VectorCollection {
        val id = rs.getLong(1)
        val name = rs.getString(2)
        val origin = VectorCollectionOrigin.valueOf(rs.getString(3))
        val dimensions = rs.getLong(4)
        val indexType = VectorDBIndex.valueOf(rs.getString(5))
        val queryMaxVectors = rs.getLong(6)
        val storeBatchSize = rs.getLong(7)
        val exists = rs.getBoolean(8)
        return VectorCollection(id, name, dimensions, queryMaxVectors, storeBatchSize, indexType, origin, exists)
    }

    private fun tableName(ctx: EContext, table: String): String = tableName(ctx.chainID, table)

    private fun tableName(chainId: Long, table: String): String = tableName("c${chainId}.$table")

    private fun tableName(table: String) = "\"$table\""

    private fun dropDatumIdSeqTable(ctx: EContext) {
        val datumSeqTableName = tableName(ctx, "${TABLE_PREFIX}datum_id_seq")
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS $datumSeqTableName")
        }
    }

    data class Vector(val datumId: Long, val context: Long, val refId: Long, val vector: String, val exclude: Boolean)
}