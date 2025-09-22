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
import net.postchain.gtx.GTXModule
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
import java.util.concurrent.ConcurrentMap

const val VECTOR_DB_QUERY_CLOSEST_OBJECTS = "query_closest_objects"
const val VECTOR_DB_EXTENSION_CONFIG_NAME = "vector_db_extension"

/** Datum ID 0 is reserved for metadata to sync collection IDs when snapshots are restored */
const val VECTOR_DB_META_DATUM_ID = 0L

class VectorDbGTXModuleContext(
        val databaseOperations: VectorDbDatabaseAccess,
) {
    var snapshotContext: SnapshotContext? = null
    lateinit var module: GTXModule
    lateinit var collectionsByName: ConcurrentMap<String, VectorCollection>
    lateinit var postchainContext: PostchainContext
    lateinit var vectorDbConfig: VectorDbConfig

    fun isInitialized(): Boolean {
        return this::module.isInitialized &&
                this::collectionsByName.isInitialized &&
                this::postchainContext.isInitialized &&
                this::vectorDbConfig.isInitialized
    }
}

open class VectorDbGTXModule(
        private val databaseOperations: VectorDbDatabaseAccess = VectorDbDatabaseAccess()
) : SimpleGTXModule<VectorDbGTXModuleContext>(
        VectorDbGTXModuleContext(databaseOperations), mapOf(), mapOf(
        VECTOR_DB_QUERY_CLOSEST_OBJECTS to Companion::queryClosestObjects)
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
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        conf.postchainContext = postchainContext
        conf.module = (configuration as GTXModuleAware).module
        conf.vectorDbConfig = configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.toObject<VectorDbConfig>()
                ?: throw UserMistake("No vector db extension config present")

        validateConfiguration(conf.vectorDbConfig)

        if (chainId == null) {
            throw ProgrammerMistake("Chain ID not set. This module is not initialized in expected order.")
        }

        logger.info { "VectorDB config: ${configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.asDict()}" }

        val ctx = postchainContext.blockBuilderStorage.openWriteConnection(chainId!!)
        try {
            initializeDb(ctx, conf.vectorDbConfig, conf.postchainContext.appConfig.databaseSchema)
        } finally {
            postchainContext.blockBuilderStorage.closeWriteConnection(ctx, true)
        }
    }

    private fun initializeDb(ctx: EContext, vectorDbConfig: VectorDbConfig, databaseSchema: String) {
        conf.collectionsByName = ConcurrentHashMap(databaseOperations.initialize(ctx, vectorDbConfig, databaseSchema)
                .associateBy { it.name })
    }

    override fun constructDatum(ctx: EContext, datumList: List<SnapshotDatum>) {
        if (datumList.isNotEmpty() && datumList[0].id == VECTOR_DB_META_DATUM_ID) {
            val snapshotMetaData = fromMetaDataGtv(datumList[0].data)

            logger.debug { "Resets vector db with collections: $snapshotMetaData" }

            databaseOperations.wipeVectorDb(ctx, conf.collectionsByName.map { it.value.id })
            databaseOperations.initializeCollectionsTable(ctx)
            databaseOperations.storeCollections(ctx, snapshotMetaData.entries.associate { it.value to it.key })

            initializeDb(ctx, conf.vectorDbConfig, conf.postchainContext.appConfig.databaseSchema)

            constructDatum(ctx, datumList.subList(1, datumList.size))
        } else {

            val vectorsPerCollection = mutableMapOf<Long, MutableList<Vector>>()
            datumList.forEach {
                val collectionVector = fromVectorDatumGtv(it.data)
                if (collectionVector.refId != null && collectionVector.vector != null) {
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
        }
    }

    override fun finalizeImport() {

        logger.debug { "Finalizing vector db snapshot import" }

        val ctx = conf.postchainContext.blockBuilderStorage.openWriteConnection(chainId!!)
        try {
            databaseOperations.getDatumIdMax(ctx)?.let {
                databaseOperations.setDatumIdSequenceOffset(ctx, it + 1)
                logger.debug { "Datum id max is $it" }
            }
        } finally {
            conf.postchainContext.blockBuilderStorage.closeWriteConnection(ctx, true)
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

    override fun initializeDB(ctx: EContext) {
        chainId = ctx.chainID
    }

    override fun initializeSnapshotContext(context: SnapshotContext) {
        conf.snapshotContext = context
    }

    override fun getMetadata() = GTXModuleMetadata(
            operations = mapOf(),
            queries = mapOf(VECTOR_DB_QUERY_CLOSEST_OBJECTS to QueryMetadata(
                    args = listOf(
                            ArgumentMetadata(name = "collection", gtvTypes = setOf(GtvType.STRING)),
                            ArgumentMetadata(name = "context", gtvTypes = setOf(GtvType.INTEGER), required = false),
                            ArgumentMetadata(name = "q_vector", gtvTypes = setOf(GtvType.STRING)),
                            ArgumentMetadata(name = "max_distance", gtvTypes = setOf(GtvType.STRING), extendedType = "decimal"),
                            ArgumentMetadata(name = "query_max_vectors", gtvTypes = setOf(GtvType.INTEGER), required = false),
                            ArgumentMetadata(name = "query_template", gtvTypes = setOf(GtvType.DICT),
                                    extendedType = "(name:text,args:map<text,gtv>)", required = false),
                    ),
                    returnType = ReturnMetadata(gtvTypes = setOf(GtvType.NULL, GtvType.BYTEARRAY, GtvType.STRING, GtvType.INTEGER, GtvType.DICT, GtvType.ARRAY, GtvType.BIGINTEGER))))
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
