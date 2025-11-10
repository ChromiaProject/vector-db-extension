package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.extensions.vectordb.config.VectorDBIndex
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import net.postchain.gtx.extensions.vectordb.config.VectorCollectionOrigin
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Statement.EXECUTE_FAILED
import java.util.LinkedList
import kotlin.use


class VectorDbDatabaseAccess{

    companion object : KLogging() {
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

        const val REUSABLE_DATUM_ID_COLUMN_DATUM_ID = "datum_id"
    }

    private var pgVectorSchema: String? = null
    private val pgVectorDataTypePrefix by lazy { if (pgVectorSchema == null) "" else "${pgVectorSchema}." }

    fun initialize(ctx: EContext) {

        logger.info { "Initializing vector db" }

        dropDatumIdSeqTable(ctx)
        initializePgVector(ctx)
        initializeCollectionsTable(ctx)
        initializeReusableDatumIdTable(ctx)
    }

    fun getAndVerifyUpdatedStaticCollections(ctx: EContext, config: VectorDbConfig): List<VectorCollection> {
        val collectionsMap = getExistingCollections(ctx)
        return config.collections
                .toList().sortedBy { it.first }
                .map { (name, tableConfig) ->
                    val existingCollection = collectionsMap[name]
                    if (existingCollection != null) {
                        if (existingCollection.dimensions != tableConfig.dimensions) {
                            throw UserMistake("Changing dimensions is not supported for collection $name")
                        }
                        if (existingCollection.index != tableConfig.indexType) {
                            throw UserMistake("Changing embedded index is not supported for collection $name")
                        }
                    }

                    val id = existingCollection?.id ?: getNextTableId(ctx)
                    VectorCollection(id, name, tableConfig, VectorCollectionOrigin.STATIC)
                }
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

    fun initializePgVector(ctx: EContext) {

        // Create PG vector extension in this schema
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")
        }

        pgVectorSchema = getPgVectorExtensionSchema(ctx)
        if (pgVectorSchema == null) {
            throw ProgrammerMistake("Failed to initialize PG vector extension")
        }

        logger.info { "Found extension in schema $pgVectorSchema" }
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

    private fun getPgVectorExtensionSchema(ctx: EContext): String? {
        return ctx.conn.createStatement().use { stmt ->
            stmt.executeQuery("""
                        SELECT n.nspname as schema_name
                        FROM pg_extension e
                                 JOIN pg_namespace n ON e.extnamespace = n.oid
                        WHERE e.extname = 'vector';
                    """).use { rs ->
                if (rs.next()) {
                    rs.getString("schema_name")
                } else null
            }
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

    private fun getNextTableId(ctx: EContext): Long {
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
                        $COLLECTION_COLUMN_EMBEDDING ${pgVectorDataTypePrefix}halfvec(${collection.dimensions}) NOT NULL)
                        """)
        }

        // Context & id index
        val contextIdIndexName = getTableIndexName(tableName, "datum_id_key")
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS "$contextIdIndexName" on $tableName ("$COLLECTION_COLUMN_CONTEXT", "$COLLECTION_COLUMN_ID")
                """
            )
        }

        // Create embedding index
        val embeddedHnswIndexName = getTableIndexName(tableName, collection.index.indexName)

        ctx.conn.createStatement().use { stmt ->
            stmt.execute(
                    """
                CREATE INDEX IF NOT EXISTS "$embeddedHnswIndexName"
                ON $tableName USING hnsw ($COLLECTION_COLUMN_EMBEDDING ${pgVectorDataTypePrefix}${collection.index.indexEmbedding})
                """
            )
        }
    }

    private fun getTableIndexName(tableName: String, suffix: String): String {
        val cleanTableName = tableName.replace("\"", "")
        return "${cleanTableName}_$suffix"
    }

    fun storeVectors(ctx: EContext, tableId: Long, vectors: List<Vector>, batchSize: Long = 300) {
        val tableName = getCollectionTableName(ctx, tableId)
        ctx.conn.prepareStatement("""
            INSERT INTO $tableName ($COLLECTION_COLUMN_DATUM_ID, $COLLECTION_COLUMN_CONTEXT, $COLLECTION_COLUMN_ID, $COLLECTION_COLUMN_EMBEDDING)
            VALUES (?, ?, ?, ?::${pgVectorDataTypePrefix}halfvec)
            """
        ).use { stmt ->
            vectors.forEachIndexed { index, vector ->
                stmt.setLong(1, vector.datumId)
                stmt.setLong(2, vector.context)
                stmt.setLong(3, vector.refId)
                stmt.setString(4, vector.vector)
                stmt.addBatch()

                if ((index + 1) % batchSize == 0L) {
                    if (stmt.executeBatch().any { it == EXECUTE_FAILED }) {
                        throw ProgrammerMistake("Failed to store vectors")
                    }
                }
            }

            if (vectors.size % batchSize != 0L) {
                if (stmt.executeBatch().any { it == EXECUTE_FAILED }) {
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

    fun deleteVectors(ctx: EContext, tableId: Long, context: Long, ids: List<Long>) {

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
    ): GtvArray {
        val tableName = getCollectionTableName(ctx, tableId)
        var ai = 1
        val vectorRsIdx = ai++
        val contextRsIdx = if (context != null) ai++ else -1
        val maxVectorsRsIdx = ai++
        val maxDistanceRsIdx = ai++
        ctx.conn.prepareStatement(
                """
                WITH nearest_results AS MATERIALIZED (
                    SELECT $COLLECTION_COLUMN_ID, $COLLECTION_COLUMN_CONTEXT, $COLLECTION_COLUMN_EMBEDDING ${pgVectorOperator(index.operator)} ?::${pgVectorDataTypePrefix}halfvec AS distance
                    FROM $tableName
                    ${if (context == null) "" else "WHERE $COLLECTION_COLUMN_CONTEXT = ?"}
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

            val result = mutableListOf<Gtv>()
            stmt.executeQuery().use {
                while (it.next()) {
                    result.add(gtv(
                            "context" to gtv(it.getLong(1)),
                            "id" to gtv(it.getLong(2)),
                            "distance" to gtv(it.getString(3))
                    ))
                }
            }
            return gtv(result)
        }
    }

    fun getDatumIdFromContextIds(ctx: EContext, tableId: Long, context: Long, ids: Set<Long>): Set<Long> {
        val tableName = getCollectionTableName(ctx, tableId)
        val datumIds = mutableSetOf<Long>()
        ctx.conn.prepareStatement(
                "SELECT $COLLECTION_COLUMN_DATUM_ID FROM $tableName WHERE $COLLECTION_COLUMN_CONTEXT = ? AND $COLLECTION_COLUMN_ID = ANY(?)"
        ).use { stmt ->
            stmt.setLong(1, context)
            stmt.setArray(2, ctx.conn.createArrayOf("bigint", ids.toTypedArray()))

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    datumIds.add(rs.getLong(1))
                }
            }
        }
        return datumIds
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
        return VECTOR_DB_META_DATUM_ID + 1
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

    private fun pgVectorOperator(op: String): String {
        return if (pgVectorSchema == null) op else "OPERATOR(${pgVectorSchema}.${op})"
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

    data class Vector(val datumId: Long, val context: Long, val refId: Long, val vector: String)
}
