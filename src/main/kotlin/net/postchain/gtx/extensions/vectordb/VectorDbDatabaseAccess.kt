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
import java.sql.Statement.EXECUTE_FAILED


class VectorDbDatabaseAccess{

    companion object : KLogging() {
        private const val TABLE_PREFIX: String = "sys.x.vectordb."

        const val TABLE_COLLECTION_META = "${TABLE_PREFIX}collection_meta"
        const val TABLE_DATUM_ID_SEQ = "${TABLE_PREFIX}datum_id_seq"
        const val TABLE_COLLECTION = "${TABLE_PREFIX}collection_"

        const val COLLECTION_IDS_COLUMN_ID = "id"
        const val COLLECTION_IDS_COLUMN_NAME = "name"
        const val COLLECTION_IDS_COLUMN_DIMENSIONS = "dimensions"
        const val COLLECTION_IDS_COLUMN_INDEX_TYPE = "index_type"
        const val COLLECTION_IDS_COLUMN_QUERY_MAX_VECTORS = "query_max_vectors"
        const val COLLECTION_IDS_COLUMN_STORE_BATCH_SIZE = "store_batch_size"
        const val COLLECTION_IDS_COLUMN_ORIGIN = "origin"

        const val DATUM_ID_SEQ_COLUMN_ID = "id"

        const val COLLECTION_COLUMN_DATUM_ID = "datum_id"
        const val COLLECTION_COLUMN_CONTEXT = "context"
        const val COLLECTION_COLUMN_ID = "id"
        const val COLLECTION_COLUMN_EMBEDDING = "embedding"
    }

    private var pgVectorSchema: String? = null
    private val pgVectorDataTypePrefix by lazy { if (pgVectorSchema == null) "" else "${pgVectorSchema}." }

    fun initialize(ctx: EContext, config: VectorDbConfig, databaseSchema: String): List<VectorCollection> {

        logger.info { "Initializing vector db" }

        initializePgVector(ctx, databaseSchema)
        initializeCollectionsTable(ctx)
        initializeDatumSeqTable(ctx)
        val collectionsMap = getCollections(ctx).toMutableMap()
        validateNoMixedCollectionOrigins(config, collectionsMap)

        val updatedTables = config.collections.map { (name, tableConfig) ->

            val collection = collectionsMap.getOrPut(name) {
                val id = getNextTableId(collectionsMap)
                VectorCollection(id, name, tableConfig, VectorCollectionOrigin.STATIC)
            }
            createOrUpdateTable(ctx, tableConfig, collection.id, databaseSchema)
            collection
        }

        storeCollections(ctx, updatedTables)

        return getCollections(ctx).values.filter {
            it.origin == VectorCollectionOrigin.DYNAMIC || config.collections.containsKey(it.name)
        }
    }

    private fun validateNoMixedCollectionOrigins(config: VectorDbConfig, collectionsMap: Map<String, VectorCollection>) {
        if (config.collections.isNotEmpty() && collectionsMap.values.any { it.origin == VectorCollectionOrigin.DYNAMIC }) {
            throw UserMistake("Database initialized with static collections, but dynamic collections exist in DB")
        }
    }

    fun wipeVectorDb(ctx: EContext, tableIds: List<Long>) {
        listOf(
                getCollectionsTableName(ctx),
                getDatumSeqTableName(ctx)
        ) + tableIds.map { getCollectionTableName(ctx, it) }
                .forEach {
                    logger.debug { "Dropping table $it" }
                    ctx.conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS $it CASCADE") }
                }
    }

    fun initializePgVector(ctx: EContext, databaseSchema: String) {

        // Create PG vector extension in this schema
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")
        }

        pgVectorSchema = getPgVectorExtensionSchema(ctx, databaseSchema)
        if (pgVectorSchema != null) {
            logger.info { "Found extension in schema $pgVectorSchema" }
        }
    }

    fun initializeCollectionsTable(ctx: EContext) {
        val collectionsTableName = getCollectionsTableName(ctx)
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS $collectionsTableName (
                        $COLLECTION_IDS_COLUMN_ID bigint NOT NULL PRIMARY KEY,
                        $COLLECTION_IDS_COLUMN_NAME text NOT NULL UNIQUE,
                        $COLLECTION_IDS_COLUMN_ORIGIN text NOT NULL,
                        $COLLECTION_IDS_COLUMN_DIMENSIONS bigint NOT NULL,
                        $COLLECTION_IDS_COLUMN_INDEX_TYPE text NOT NULL,
                        $COLLECTION_IDS_COLUMN_QUERY_MAX_VECTORS bigint NOT NULL,
                        $COLLECTION_IDS_COLUMN_STORE_BATCH_SIZE bigint NOT NULL
                        )
                        """)
        }
    }

    fun initializeDatumSeqTable(ctx: EContext) {
        val datumSeqTableName = getDatumSeqTableName(ctx)
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE IF NOT EXISTS $datumSeqTableName ($DATUM_ID_SEQ_COLUMN_ID bigint NOT NULL)")
        }
        setDatumIdSequenceOffset(ctx, getDatumIdSequenceOffset(ctx))
    }

    private fun getPgVectorExtensionSchema(ctx: EContext, databaseSchema: String): String? {
        return ctx.conn.createStatement().use { stmt ->
            stmt.executeQuery("""
                        SELECT n.nspname as schema_name
                        FROM pg_extension e
                                 JOIN pg_namespace n ON e.extnamespace = n.oid
                        WHERE e.extname = 'vector';
                    """).use { rs ->
                if (rs.next()) {
                    val schema = rs.getString("schema_name")
                    if (schema != databaseSchema) schema else null
                } else null
            }
        }
    }

    fun storeCollections(ctx: EContext, collections: List<VectorCollection>) {
        ctx.conn.prepareStatement("""
            INSERT INTO ${getCollectionsTableName(ctx)}
            ($COLLECTION_IDS_COLUMN_ID, $COLLECTION_IDS_COLUMN_NAME, $COLLECTION_IDS_COLUMN_ORIGIN, $COLLECTION_IDS_COLUMN_DIMENSIONS, $COLLECTION_IDS_COLUMN_INDEX_TYPE, $COLLECTION_IDS_COLUMN_QUERY_MAX_VECTORS, $COLLECTION_IDS_COLUMN_STORE_BATCH_SIZE) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ($COLLECTION_IDS_COLUMN_ID) DO NOTHING
            """
        ).use { stmt ->
            collections.forEach { collection ->
                stmt.setLong(1, collection.id)
                stmt.setString(2, collection.name)
                stmt.setString(3, collection.origin.name)
                stmt.setLong(4, collection.dimensions)
                stmt.setString(5, collection.index.name)
                stmt.setLong(6, collection.maxVectors)
                stmt.setLong(7, collection.storeBatchSize)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    fun createCollection(ctx: EContext, collectionName: String, config: VectorDbCollectionConfig, databaseSchema: String): VectorCollection {
        val collectionsMap = getCollections(ctx).toMutableMap()
        val collection = collectionsMap.getOrPut(collectionName) {
            val id = getNextTableId(collectionsMap)
            VectorCollection(id, collectionName, config, VectorCollectionOrigin.DYNAMIC)
        }
        // TODO: Check if collection already exists and throw error
        createOrUpdateTable(ctx, config, collection.id, databaseSchema)

        storeCollections(ctx, listOf(collection))
        return collection
    }

    fun deleteCollection(ctxt: EContext, collection: VectorCollection) {
        val tableName = getCollectionTableName(ctxt, collection.id)
        ctxt.conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS $tableName CASCADE")
        }
        deleteCollectionIdEntry(ctxt, collection)
    }

    fun updateCollection(ctxt: TxEContext, collection: VectorCollection, queryMaxVectors: Long?, storeBatchSize: Long?): VectorCollection {
        val tableName = getCollectionsTableName(ctxt)
        val updatedQueryMaxVectors = queryMaxVectors ?: collection.maxVectors
        val updatedStoreBatchSize = storeBatchSize ?: collection.storeBatchSize

        ctxt.conn.createStatement().use { stmt ->
            stmt.execute("UPDATE $tableName SET $COLLECTION_IDS_COLUMN_QUERY_MAX_VECTORS = $updatedQueryMaxVectors, $COLLECTION_IDS_COLUMN_STORE_BATCH_SIZE = $updatedStoreBatchSize WHERE $COLLECTION_IDS_COLUMN_ID = ${collection.id}")
        }
        return collection.copy(maxVectors = updatedQueryMaxVectors, storeBatchSize = updatedStoreBatchSize)
    }

    private fun deleteCollectionIdEntry(ctx: EContext, collection: VectorCollection) {
        val tableName = getCollectionsTableName(ctx)
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("DELETE FROM $tableName WHERE $COLLECTION_IDS_COLUMN_ID = ${collection.id}")
        }
    }

    private fun getNextTableId(collectionsMap: MutableMap<String, VectorCollection>): Long {
        return collectionsMap.values.maxByOrNull { it.id }?.let {
            it.id + 1
        } ?: 0L
    }

    private fun createOrUpdateTable(ctx: EContext, config: VectorDbCollectionConfig, tableId: Long, databaseSchema: String) {

        val tableName = getCollectionTableName(ctx, tableId)

        // Create the vector table
        ctx.conn.createStatement().use { stmt ->
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS $tableName (
                        $COLLECTION_COLUMN_DATUM_ID bigint NOT NULL PRIMARY KEY,
                        $COLLECTION_COLUMN_CONTEXT bigint NOT NULL,
                        $COLLECTION_COLUMN_ID bigint NOT NULL,
                        $COLLECTION_COLUMN_EMBEDDING ${pgVectorDataTypePrefix}halfvec(${config.dimensions}) NOT NULL)
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
        val embeddedHnswIndexName = getTableIndexName(tableName, config.indexType.indexName)

        // Make sure we don't add a new index type - if we want to support this we need to expand the query part
        // to provide the distance query operator for each index
        val embeddingIndexList = VectorDBIndex.entries.map { getTableIndexName(tableName, it.indexName) }
        val tableEmbeddingIndexes = getTableIndexes(ctx, databaseSchema, tableName)
                .filter { embeddingIndexList.contains(it) }
        if (tableEmbeddingIndexes.any { it != embeddedHnswIndexName }) {
            throw UserMistake("Changing embedded index is not supported")
        } else {

            logger.info { "Creating embedding index of type ${config.indexType}" }

            ctx.conn.createStatement().use { stmt ->
                stmt.execute(
                        """
                    CREATE INDEX IF NOT EXISTS "$embeddedHnswIndexName"
                    ON $tableName USING hnsw ($COLLECTION_COLUMN_EMBEDDING ${pgVectorDataTypePrefix}${config.indexType.indexEmbedding})
                    """
                )
            }
        }
    }

    private fun getTableIndexName(tableName: String, suffix: String): String {
        val cleanTableName = tableName.replace("\"", "")
        return "${cleanTableName}_$suffix"
    }

    private fun getTableIndexes(ctx: EContext, databaseSchema: String, tableName: String): Set<String> {
        val results = mutableSetOf<String>()
        val resultSet = ctx.conn.metaData.getIndexInfo(null, databaseSchema, tableName.replace("\"", ""), false, false)
        while (resultSet.next()) {
            val indexName = resultSet.getString("INDEX_NAME")
            results.add(indexName)
        }

        return results
    }

    fun storeVectors(ctx: EContext, tableId: Long, vectors: List<Vector>, batchSize: Long = 300) {
        // TODO: When vectors size is different from dimension, throw user friendly (non-sql) error
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
    }

    fun deleteVectors(ctx: TxEContext, tableId: Long, context: Long, ids: List<Long>) {
        val tableName = getCollectionTableName(ctx, tableId)
        val rowsAffected = ctx.conn.prepareStatement(
                "DELETE FROM $tableName WHERE $COLLECTION_COLUMN_CONTEXT = ? AND $COLLECTION_COLUMN_ID = ANY(?)"
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


    fun getCollectionsTableName(ctx: EContext): String {
        return tableName(ctx, TABLE_COLLECTION_META)
    }

    fun getDatumSeqTableName(ctx: EContext): String {
        return tableName(ctx, TABLE_DATUM_ID_SEQ)
    }

    fun getDatumIdSequenceOffset(ctx: EContext): Long {
        val tableName = getDatumSeqTableName(ctx)
        ctx.conn.createStatement().use { stmt -> stmt.executeQuery("SELECT $DATUM_ID_SEQ_COLUMN_ID FROM $tableName").use { rs ->
            if (rs.next()) {
                return rs.getLong(1)
            }
        }}
        return VECTOR_DB_META_DATUM_ID + 1
    }

    fun setDatumIdSequenceOffset(ctx: EContext, id: Long) {
        val tableName = getDatumSeqTableName(ctx)
        val affected = ctx.conn.createStatement().use { stmt ->
            stmt.executeUpdate("UPDATE $tableName SET $DATUM_ID_SEQ_COLUMN_ID = $id")
        }
        if (affected == 0) {
            ctx.conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO $tableName ($DATUM_ID_SEQ_COLUMN_ID) VALUES($id)")
            }
        } else if (affected > 1) {
            throw ProgrammerMistake("Incorrect datum id sequence state")
        }
    }

    fun getCollections(ctx: EContext): Map<String, VectorCollection> {
        val tableName = getCollectionsTableName(ctx)
        val collectionsMap = mutableMapOf<String, VectorCollection>()
        ctx.conn.createStatement().use { stmt -> stmt.executeQuery("SELECT $COLLECTION_IDS_COLUMN_ID, $COLLECTION_IDS_COLUMN_NAME, $COLLECTION_IDS_COLUMN_ORIGIN, $COLLECTION_IDS_COLUMN_DIMENSIONS, $COLLECTION_IDS_COLUMN_INDEX_TYPE, $COLLECTION_IDS_COLUMN_QUERY_MAX_VECTORS, $COLLECTION_IDS_COLUMN_STORE_BATCH_SIZE FROM $tableName").use { rs ->
            while (rs.next()) {
                val id = rs.getLong(1)
                val name = rs.getString(2)
                val origin = VectorCollectionOrigin.valueOf(rs.getString(3))
                val dimensions = rs.getLong(4)
                val indexType = VectorDBIndex.valueOf(rs.getString(5))
                val queryMaxVectors = rs.getLong(6)
                val storeBatchSize = rs.getLong(7)
                collectionsMap[name] = VectorCollection(id, name, dimensions, queryMaxVectors, storeBatchSize, indexType, origin)
            }
        }}
        return collectionsMap
    }

    fun getDatumIdMax(ctx: EContext): Long? {
        return getCollections(ctx)
                .map { getCollectionTableName(ctx, it.value.id) }
                .mapNotNull {
                    ctx.conn.createStatement().use { stmt -> stmt.executeQuery("SELECT MAX($COLLECTION_COLUMN_DATUM_ID) FROM $it").use { rs ->
                        if (rs.next()) {
                            rs.getLong(1)
                        } else {
                            null
                        }
                    }}
                }
                .maxOrNull()
    }

    fun getCollectionTableName(ctx: EContext, id: Long): String {
        return tableName(ctx, "${TABLE_COLLECTION}${id}")
    }

    private fun pgVectorOperator(op: String): String {
        return if (pgVectorSchema == null) op else "OPERATOR(${pgVectorSchema}.${op})"
    }

    private fun tableName(ctx: EContext, table: String): String = tableName(ctx.chainID, table)

    private fun tableName(chainId: Long, table: String): String = tableName("c${chainId}.$table")

    private fun tableName(table: String) = "\"$table\""

    data class Vector(val datumId: Long, val context: Long, val refId: Long, val vector: String)
}
