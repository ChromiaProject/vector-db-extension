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
import net.postchain.gtx.extensions.vectordb.config.VectorCollectionOrigin
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

const val VECTOR_DB_QUERY_CLOSEST_OBJECTS = "query_closest_objects"
const val VECTOR_DB_GET_COLLECTIONS = "get_vector_collections"
const val VECTOR_DB_EXTENSION_CONFIG_NAME = "vector_db_extension"

/** Datum ID 0 is reserved for metadata to sync collection IDs when snapshots are restored */
const val VECTOR_DB_META_DATUM_ID = 0L

open class VectorDbGTXModule(
        private val databaseOperations: VectorDbDatabaseAccess = VectorDbDatabaseAccess()
) : SimpleGTXModule<VectorDbGTXModuleContext>(
        VectorDbGTXModuleContext(databaseOperations), mapOf(), mapOf(
                VECTOR_DB_QUERY_CLOSEST_OBJECTS to Companion::queryClosestObjects,
                VECTOR_DB_GET_COLLECTIONS to Companion::getVectorCollections
        )
), PostchainContextAware, MetadataProvider, SnapshotAware {

    private var chainId: Long? = null

    companion object : KLogging() {
        fun queryClosestObjects(moduleContext: VectorDbGTXModuleContext, ctx: EContext, args: Gtv): Gtv {
            if (!moduleContext.isInitialized()) {
                throw UserMistake("Module is not initialized")
            }

            val collectionArg = args["collection"]?.asString() ?: throw UserMistake("No collection argument supplied")
            val collection = moduleContext.collectionsByName[collectionArg] ?: throw UserMistake("Collection $collectionArg not found")
            val context = args["context"]?.asInteger()
            val vectorQuery = args["q_vector"]?.asString() ?: throw UserMistake("No q_vector argument supplied")
            val maxDistance = BigDecimal(args["max_distance"]?.asString()
                    ?: throw UserMistake("No max_distance argument supplied"))
            val maxVectors = args["query_max_vectors"]?.asInteger()?.let {
                if (it > collection.maxVectors) {
                    throw UserMistake("query_max_vectors ($it) exceeds the maximum of ${collection.maxVectors}")
                }
                it
            } ?: collection.maxVectors
            val queryTemplate = args["query_template"]?.asDict()

            val vectorResult = moduleContext.databaseOperations.queryClosestObjects(ctx, collection.id, context,
                    vectorQuery, maxDistance, maxVectors, collection.index)

            return if (queryTemplate == null) {
                vectorResult
            } else {
                val queryTemplateName = queryTemplate["name"]?.asString()
                        ?: throw UserMistake("No name argument supplied to query_template")
                val queryTemplateArgs = queryTemplate["args"]?.asDict() ?: mapOf()
                return moduleContext.module.query(ctx, queryTemplateName,
                        gtv(mapOf("closest_results" to vectorResult) + queryTemplateArgs))
            }
        }

        fun getVectorCollections(moduleContext: VectorDbGTXModuleContext, ctx: EContext, args: Gtv): Gtv {
            val vectorCollections = moduleContext.collectionsByName.values.map { collection ->
                gtv(mapOf(
                        "name" to gtv(collection.name),
                        "dimensions" to gtv(collection.dimensions),
                        "index" to gtv(collection.index.name.lowercase()),
                        "query_max_vectors" to gtv(collection.maxVectors),
                        "store_batch_size" to gtv(collection.storeBatchSize)
                ))
            }
            return gtv(vectorCollections)
        }
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        conf.postchainContext = postchainContext
        conf.module = (configuration as GTXModuleAware).module
        conf.vectorDbConfig = configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.toObject<VectorDbConfig>()
                ?: VectorDbConfig.DEFAULT_CONFIG

        validateConfiguration(conf.vectorDbConfig)

        logger.info { "VectorDB config: ${configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.asDict()}" }

        chainId = configuration.chainID
        val ctx = postchainContext.blockBuilderStorage.openWriteConnection(configuration.chainID)
        try {
            initializeDb(ctx, conf.vectorDbConfig, conf.postchainContext.appConfig.databaseSchema)
        } finally {
            postchainContext.blockBuilderStorage.closeWriteConnection(ctx, true)
        }
    }

    private fun initializeDb(ctx: EContext, vectorDbConfig: VectorDbConfig, databaseSchema: String) {
        databaseOperations.initialize(ctx, databaseSchema)

        // Should we move the static update of collections to the init in the block builder extension to
        // make it part of a block? We still need to sync assigned ids in db with config however
        val currentCollections = databaseOperations.getCollections(ctx)
        databaseOperations.updateStaticCollections(ctx, vectorDbConfig)
        databaseOperations.createOrUpdateCollectionTables(ctx, databaseSchema)
        conf.emitCollections = currentCollections != databaseOperations.getExistingCollections(ctx)

        val collectionOrigins = databaseOperations.getCollectionOrigins(ctx)
        if (collectionOrigins.size > 1) {
            throw UserMistake("Database initialized with static collections, but dynamic collections exist in DB")
        }
        conf.collectionOriginMode = collectionOrigins.firstOrNull() ?: VectorCollectionOrigin.DYNAMIC
        conf.collectionsByName = ConcurrentHashMap(databaseOperations.getExistingCollections(ctx).filterValues {
            it.origin == VectorCollectionOrigin.DYNAMIC || vectorDbConfig.collections.containsKey(it.name)
        })
    }

    override fun constructDatum(ctx: EContext, datumList: List<SnapshotDatum>) {
        if (datumList.isNotEmpty() && datumList[0].id == VECTOR_DB_META_DATUM_ID) {
            val snapshotMetaData = fromMetaDataGtv(datumList[0].data)

            logger.debug { "Resets vector db with collections: $snapshotMetaData" }

            databaseOperations.wipeVectorDb(ctx)
            databaseOperations.storeCollections(ctx, snapshotMetaData.values.toList())

            initializeDb(ctx, conf.vectorDbConfig, conf.postchainContext.appConfig.databaseSchema)
            conf.emitCollections = false

            constructDatum(ctx, datumList.subList(1, datumList.size))
        } else {

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
                            .add(Vector(it.id, collectionVector.context, collectionVector.refId, collectionVector.vector))
                }
            }

            vectorsPerCollection.forEach { (cid, vectors) ->
                val collection = conf.collectionsByName.values.find { it.id == cid }
                if (collection == null) {
                    throw ProgrammerMistake("Received snapshot datum for collection $cid which is not registered in the module")
                }
                databaseOperations.storeVectors(ctx, cid, vectors, collection.storeBatchSize)
            }
            databaseOperations.addReusableDatumIds(ctx, reusableDatumIds)
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
                VectorDbDatumMapper.toMetaDataGtv(emptyMap()), false))
    }

    override fun initializeDB(ctx: EContext) {
    }

    override fun initializeSnapshotContext(context: SnapshotContext) {
        conf.snapshotContext = context
    }

    override fun getMetadata() = GTXModuleMetadata(
            operations = mapOf(),
            queries = mapOf(
                    VECTOR_DB_QUERY_CLOSEST_OBJECTS to QueryMetadata(
                            args = listOf(
                                    ArgumentMetadata(name = "collection", gtvTypes = setOf(GtvType.STRING)),
                                    ArgumentMetadata(name = "context", gtvTypes = setOf(GtvType.INTEGER), required = false),
                                    ArgumentMetadata(name = "q_vector", gtvTypes = setOf(GtvType.STRING)),
                                    ArgumentMetadata(name = "max_distance", gtvTypes = setOf(GtvType.STRING), extendedType = "decimal"),
                                    ArgumentMetadata(name = "query_max_vectors", gtvTypes = setOf(GtvType.INTEGER), required = false),
                                    ArgumentMetadata(name = "query_template", gtvTypes = setOf(GtvType.DICT),
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
        return listOf(VectorDbEventProcessor(databaseOperations, conf))
    }

    override fun getPermanentDatumIdMax(ctx: EContext): Long? = null

    override fun getPermanentDatums(ctx: EContext, datumIdFrom: Long, datumHandler: (datum: SnapshotDatum?) -> Boolean) {
        datumHandler(null)
    }
}
