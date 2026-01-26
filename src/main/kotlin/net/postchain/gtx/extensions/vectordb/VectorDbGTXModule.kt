package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.snapshot.SnapshotDatum
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvType
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.ArgumentMetadata
import net.postchain.gtx.GTXModuleAware
import net.postchain.gtx.GTXModuleMetadata
import net.postchain.gtx.MetadataProvider
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.QueryMetadata
import net.postchain.gtx.ReturnMetadata
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.SnapshotAware
import net.postchain.gtx.SnapshotContext
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseAccess.Vector
import net.postchain.gtx.extensions.vectordb.VectorDbDatumMapper.Companion.fromMetaDataGtv
import net.postchain.gtx.extensions.vectordb.VectorDbDatumMapper.Companion.fromVectorDatumGtv
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

open class VectorDbGTXModule(
        private val db: VectorDbDatabaseAccess = VectorDbDatabaseAccess()
) : SimpleGTXModule<VectorDbGTXModuleContext>(
        VectorDbGTXModuleContext(db), mapOf(), mapOf(
                VECTOR_DB_QUERY_CLOSEST_OBJECTS to Companion::queryClosestObjects,
                VECTOR_DB_GET_COLLECTIONS to Companion::getVectorCollections
        )
), PostchainContextAware, MetadataProvider, SnapshotAware {

    companion object : KLogging() {

        const val VECTOR_DB_QUERY_CLOSEST_OBJECTS = "query_closest_objects"
        const val VECTOR_DB_GET_COLLECTIONS = "get_vector_collections"
        const val VECTOR_DB_EXTENSION_CONFIG_NAME = "vector_db_extension"

        private const val ARG_COLLECTION = "collection"
        private const val ARG_CONTEXT = "context"
        private const val ARG_Q_VECTOR = "q_vector"
        private const val ARG_MAX_DISTANCE = "max_distance"
        private const val ARG_QUERY_MAX_VECTORS = "query_max_vectors"

        /** Datum ID 0 is reserved for metadata to sync collection IDs when snapshots are restored */
        const val VECTOR_DB_META_DATUM_ID = 0L

        const val VECTOR_DB_QUERY_ARG_QUERY_TEMPLATE = "query_template"

        fun queryClosestObjects(moduleContext: VectorDbGTXModuleContext, ctx: EContext, args: Gtv): Gtv {
            if (!moduleContext.isInitialized()) {
                throw UserMistake("Module is not initialized")
            }

            val collectionArg = args[ARG_COLLECTION]?.asString() ?: throw UserMistake("No collection argument supplied")
            val collection = moduleContext.collectionsByName[collectionArg] ?: throw UserMistake("Collection $collectionArg not found")
            val context = args[ARG_CONTEXT]?.asIntegerOrNull()
            val vectorQuery = args[ARG_Q_VECTOR]?.asString() ?: throw UserMistake("No q_vector argument supplied")
            requireValidVector(vectorQuery, collection.dimensions)
            val maxDistance = BigDecimal(args[ARG_MAX_DISTANCE]?.asString()
                    ?: throw UserMistake("No $ARG_MAX_DISTANCE argument supplied"))
            val maxVectors = args[ARG_QUERY_MAX_VECTORS]?.asIntegerOrNull()?.let {
                if (it > collection.queryMaxVectors) {
                    throw UserMistake("$ARG_QUERY_MAX_VECTORS ($it) exceeds the maximum of ${collection.queryMaxVectors}")
                }
                it
            } ?: collection.queryMaxVectors
            val queryTemplate = args[VECTOR_DB_QUERY_ARG_QUERY_TEMPLATE]?.asDict()

            val vectorResult = moduleContext.databaseOperations.queryClosestObjects(ctx, collection.id, context,
                    vectorQuery, maxDistance, maxVectors, collection.index)
            val vectorGtvResult = gtv(vectorResult.map { gtv(
                    "context" to gtv(it.context),
                    "id" to gtv(it.id),
                    "distance" to gtv(it.distance)
            ) })

            return if (queryTemplate == null) {
                vectorGtvResult
            } else {
                val queryTemplateName = queryTemplate["name"]?.asString()
                        ?: throw UserMistake("No name argument supplied to query_template")
                val queryTemplateArgs = queryTemplate["args"]?.asDict() ?: mapOf()
                moduleContext.module.query(ctx, queryTemplateName,
                        gtv(mapOf("closest_results" to vectorGtvResult) + queryTemplateArgs))
            }
        }

        @Suppress("UNUSED_PARAMETER")
        fun getVectorCollections(moduleContext: VectorDbGTXModuleContext, ctx: EContext, args: Gtv): Gtv {
            val vectorCollections = moduleContext.collectionsByName.values.map { collection ->
                gtv(mapOf(
                        "name" to gtv(collection.name),
                        "dimensions" to gtv(collection.dimensions),
                        "index" to gtv(collection.index.name.lowercase()),
                        "query_max_vectors" to gtv(collection.queryMaxVectors),
                        "store_batch_size" to gtv(collection.storeBatchSize)
                ))
            }
            return gtv(vectorCollections)
        }

        fun getAndEnsureOneOriginMode(db: VectorDbDatabaseAccess, ctx: EContext, pendingStaticCollections: Boolean): VectorCollectionOrigin {
            val collectionOrigins = db.getCollectionOrigins(ctx).toMutableSet()
            if (pendingStaticCollections) {
                collectionOrigins.add(VectorCollectionOrigin.STATIC)
            }
            if (collectionOrigins.size > 1) {
                throw UserMistake("Database initialized with static collections, but dynamic collections exist in DB")
            }
            return collectionOrigins.firstOrNull() ?: VectorCollectionOrigin.DYNAMIC
        }

        fun getActiveCollections(db: VectorDbDatabaseAccess, ctx: EContext, vectorDbConfig: VectorDbConfig): ConcurrentHashMap<String, VectorCollection> {
            val collectionsByName = ConcurrentHashMap(db.getExistingCollections(ctx).filterValues {
                it.origin == VectorCollectionOrigin.DYNAMIC || vectorDbConfig.collections.containsKey(it.name)
            })
            return collectionsByName
        }
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext, ctx: EContext) {
        conf.module = (configuration as GTXModuleAware).module
        conf.vectorDbConfig = configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.toObject<VectorDbConfig>()
                ?: VectorDbConfig.DEFAULT_CONFIG

        validateConfiguration(conf.vectorDbConfig)

        logger.info { "VectorDB config: ${configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.asDict()}" }

        // Load existing active collections before processing any static update
        conf.collectionsByName = ConcurrentHashMap(getActiveCollections(db, ctx, conf.vectorDbConfig))

        // Validate any pending static collection updates
        val updatedCollections = getAndVerifyUpdatedStaticCollections(ctx, conf.vectorDbConfig)
        val pendingStaticCollections = updatedCollections.isNotEmpty()
        conf.collectionOriginMode = getAndEnsureOneOriginMode(db, ctx, pendingStaticCollections)

        /** Update static collections, refresh in memory map after block built in [VectorDbEventProcessor.init] */
        conf.refreshCollections = pendingStaticCollections
        db.storeCollections(ctx, updatedCollections)
        db.createOrUpdateCollectionTables(ctx)
    }

    override fun initializeDB(ctx: EContext) {
        db.initialize(ctx)
        db.createOrUpdateCollectionTables(ctx)
    }

    override fun constructDatum(ctx: EContext, datumList: List<SnapshotDatum>) {
        if (datumList.isNotEmpty() && datumList[0].id == VECTOR_DB_META_DATUM_ID) {
            processMetadataSnapshot(ctx, datumList)
        } else {
            processVectorSnapshot(ctx, datumList)
        }
    }

    private fun processMetadataSnapshot(ctx: EContext, datumList: List<SnapshotDatum>) {
        val snapshotMetaData = fromMetaDataGtv(datumList[0].data)
        logger.debug { "Resets vector db with collections: $snapshotMetaData" }

        db.wipeVectorDb(ctx)
        db.initialize(ctx)
        db.storeCollections(ctx, snapshotMetaData.values.toList())
        db.createOrUpdateCollectionTables(ctx)

        conf.collectionOriginMode = getAndEnsureOneOriginMode(db, ctx, false)
        conf.collectionsByName = ConcurrentHashMap(getActiveCollections(db, ctx, conf.vectorDbConfig))

        constructDatum(ctx, datumList.subList(1, datumList.size))
    }

    private fun processVectorSnapshot(ctx: EContext, datumList: List<SnapshotDatum>) {
        val existingCollectionIds = conf.collectionsByName.values.map { it.id }.toSet()
        val reusableDatumIds = mutableSetOf<Long>()
        val vectorsPerCollection = mutableMapOf<Long, MutableList<Vector>>()

        datumList.forEach {
            val collectionVector = fromVectorDatumGtv(it.data)
            if (!existingCollectionIds.contains(collectionVector.collectionId)) {
                logger.trace { "Ignored datum ${it.id} for non existing collection ${collectionVector.collectionId}" }
                reusableDatumIds.add(it.id)
            } else if (collectionVector.refId == null || collectionVector.vector == null) {
                logger.trace { "Ignored removed datum ${it.id}" }
                reusableDatumIds.add(it.id)
            } else {
                vectorsPerCollection.getOrPut(collectionVector.collectionId) { mutableListOf() }
                        .add(Vector(it.id, collectionVector.context, collectionVector.refId, collectionVector.vector, collectionVector.exclude))
            }
        }

        vectorsPerCollection.forEach { (cid, vectors) ->
            val collection = conf.collectionsByName.values.find { it.id == cid }
                    ?: throw ProgrammerMistake("Received snapshot datum for collection $cid which is not registered in the module")
            db.storeVectors(ctx, cid, vectors, collection.storeBatchSize)
        }
        db.addReusableDatumIds(ctx, reusableDatumIds)
    }

    private fun getAndVerifyUpdatedStaticCollections(ctx: EContext, config: VectorDbConfig): List<VectorCollection> {
        val collectionsMap = db.getExistingCollections(ctx)
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

                    val id = existingCollection?.id ?: db.getNextTableId(ctx)
                    VectorCollection(id, name, tableConfig, VectorCollectionOrigin.STATIC)
                }
    }

    private fun validateConfiguration(vectorDbConfig: VectorDbConfig) {
        vectorDbConfig.collections.forEach { (name, table) ->
            try {
                table.validate()
            } catch (e: Exception) {
                throw UserMistake("Invalid table configuration for $name: ${e.message}")
            }
        }
    }

    override fun getInitialDatums(ctx: EContext): List<SnapshotDatum> {
        return listOf(SnapshotDatum(VECTOR_DB_META_DATUM_ID,
                VectorDbDatumMapper.toMetaDataGtv(emptyList()), false))
    }

    override fun initializeSnapshotContext(context: SnapshotContext) {
        conf.snapshotContext = context
    }

    override fun getMetadata() = GTXModuleMetadata(
            operations = mapOf(),
            queries = mapOf(
                    VECTOR_DB_QUERY_CLOSEST_OBJECTS to QueryMetadata(
                            args = listOf(
                                    ArgumentMetadata(name = ARG_COLLECTION, gtvTypes = setOf(GtvType.STRING)),
                                    ArgumentMetadata(name = ARG_CONTEXT, gtvTypes = setOf(GtvType.INTEGER), required = false),
                                    ArgumentMetadata(name = ARG_Q_VECTOR, gtvTypes = setOf(GtvType.STRING)),
                                    ArgumentMetadata(name = ARG_MAX_DISTANCE, gtvTypes = setOf(GtvType.STRING), extendedType = "decimal"),
                                    ArgumentMetadata(name = ARG_QUERY_MAX_VECTORS, gtvTypes = setOf(GtvType.INTEGER), required = false),
                                    ArgumentMetadata(name = VECTOR_DB_QUERY_ARG_QUERY_TEMPLATE, gtvTypes = setOf(GtvType.DICT),
                                            extendedType = "(name:text,args:map<text,gtv>)", required = false),
                            ),
                            returnType = ReturnMetadata(gtvTypes = setOf(GtvType.NULL, GtvType.BYTEARRAY, GtvType.STRING, GtvType.INTEGER, GtvType.DICT, GtvType.ARRAY, GtvType.BIGINTEGER))),
                    VECTOR_DB_GET_COLLECTIONS to QueryMetadata(
                            args = emptyList(),
                            returnType = ReturnMetadata(gtvTypes = setOf(GtvType.ARRAY)))
            )
    )

    override fun getSpecialTxExtensions() = emptyList<GTXSpecialTxExtension>()

    override fun makeBlockBuilderExtensions(): List<BaseBlockBuilderExtension> {
        return listOf(VectorDbEventProcessor(db, conf))
    }

    override fun getPermanentDatumIdMax(ctx: EContext): Long? = null

    override fun getPermanentDatums(ctx: EContext, datumIdFrom: Long, datumHandler: (datum: SnapshotDatum?) -> Boolean) {
        datumHandler(null)
    }
}

fun Gtv?.asIntegerOrNull(): Long? {
    return if (this == null || this.isNull()) null else asInteger()
}
