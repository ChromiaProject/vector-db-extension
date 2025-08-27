package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.base.BaseBlockBuilderExtension
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
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigDecimal

const val VECTOR_DB_QUERY_CLOSEST_OBJECTS = "query_closest_objects"

class VectorDbGTXModuleContext(
        val databaseOperations: VectorDbDatabaseOperations,
) {
    lateinit var module: GTXModule
    lateinit var vectorDbConfig: VectorDbConfig

    fun isInitialized(): Boolean {
        return this::module.isInitialized && this::vectorDbConfig.isInitialized
    }
}

open class VectorDbGTXModule(
        private val databaseOperations: VectorDbDatabaseOperations = VectorDbDatabaseOperations()
) : SimpleGTXModule<VectorDbGTXModuleContext>(
        VectorDbGTXModuleContext(databaseOperations), mapOf(), mapOf(
        VECTOR_DB_QUERY_CLOSEST_OBJECTS to Companion::queryClosestObjects,
)
), PostchainContextAware, MetadataProvider {

    private var chainId: Long? = null

    companion object : KLogging() {
        fun queryClosestObjects(moduleContext: VectorDbGTXModuleContext, ctx: EContext, args: Gtv): Gtv {
            if (!moduleContext.isInitialized()) {
                throw UserMistake("Module is not initialized")
            }

            val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
            val vectorQuery = args["q_vector"]?.asString() ?: throw UserMistake("No q_vector argument supplied")
            val maxDistance = BigDecimal(args["max_distance"]?.asString()
                    ?: throw UserMistake("No max_distance argument supplied"))
            val maxVectors = args["max_vectors"]?.asInteger()?.let {
                if (it > moduleContext.vectorDbConfig.maxVectors) {
                    throw UserMistake("max_vectors ($it) exceeds the maximum of ${moduleContext.vectorDbConfig.maxVectors}")
                }
                it
            } ?: moduleContext.vectorDbConfig.maxVectors
            val queryTemplate = args["query_template"]?.asDict()

            val vectorResult = moduleContext.databaseOperations.queryClosestObjects(ctx, context, vectorQuery, maxDistance, maxVectors)

            return if (queryTemplate == null) {
                vectorResult
            } else {
                val queryTemplateType = queryTemplate["type"]?.asString()
                        ?: throw UserMistake("No type argument supplied to query_template")
                val queryTemplateArgs = queryTemplate["args"]?.asDict() ?: mapOf()
                return moduleContext.module.query(ctx, queryTemplateType,
                        gtv(mapOf("closest_results" to vectorResult) + queryTemplateArgs))
            }
        }
    }


    override fun getMetadata() = GTXModuleMetadata(
            operations = mapOf(),
            queries = mapOf(VECTOR_DB_QUERY_CLOSEST_OBJECTS to QueryMetadata(
                    args = listOf(
                            ArgumentMetadata(name = "context", gtvTypes = setOf(GtvType.INTEGER)),
                            ArgumentMetadata(name = "q_vector", gtvTypes = setOf(GtvType.STRING)),
                            ArgumentMetadata(name = "max_distance", gtvTypes = setOf(GtvType.STRING), extendedType = "decimal"),
                            ArgumentMetadata(name = "max_vectors", gtvTypes = setOf(GtvType.INTEGER), required = false),
                            ArgumentMetadata(name = "query_template", gtvTypes = setOf(GtvType.DICT),
                                    extendedType = "(type:text,args:map<text,gtv>)", required = false),
                    ),
                    returnType = ReturnMetadata(gtvTypes = setOf(GtvType.NULL, GtvType.BYTEARRAY, GtvType.STRING, GtvType.INTEGER, GtvType.DICT, GtvType.ARRAY, GtvType.BIGINTEGER))))
    )

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        conf.module = (configuration as GTXModuleAware).module
        conf.vectorDbConfig = configuration.rawConfig["vector_db_extension"]?.toObject<VectorDbConfig>()
                ?: throw UserMistake("No vector db extension config present")

        if (chainId != null) {

            logger.info { "VectorDB config: ${configuration.rawConfig["vector_db_extension"]?.asDict()}" }

            val ctx = postchainContext.blockBuilderStorage.openWriteConnection(chainId!!)
            try {
                databaseOperations.initialize(ctx, conf.vectorDbConfig)
            } finally {
                postchainContext.blockBuilderStorage.closeWriteConnection(ctx, true)
            }
        }
    }

    override fun initializeDB(ctx: EContext) {
        chainId = ctx.chainID
    }

    override fun getSpecialTxExtensions() = emptyList<GTXSpecialTxExtension>()

    override fun makeBlockBuilderExtensions(): List<BaseBlockBuilderExtension> {
        return listOf(VectorDbEventProcessor(databaseOperations, conf.vectorDbConfig))
    }
}
