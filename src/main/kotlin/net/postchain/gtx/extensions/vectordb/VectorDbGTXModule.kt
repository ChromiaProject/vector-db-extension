package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXModuleAware
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigDecimal

const val VECTOR_DB_QUERY_CLOSEST_OBJECTS = "query_closest_objects"
const val VECTOR_DB_QUERY_CLOSEST_OBJECTS_DISTANCE = "query_closest_objects_distance"

class VectorDbGTXModuleContext(
        val databaseOperations: VectorDbDatabaseOperations,
) {
    lateinit var module: GTXModule
}

class VectorDbGTXModule(
        private val databaseOperations: VectorDbDatabaseOperations = VectorDbDatabaseOperations()
) : SimpleGTXModule<VectorDbGTXModuleContext>(
        VectorDbGTXModuleContext(databaseOperations), mapOf(), mapOf(
        VECTOR_DB_QUERY_CLOSEST_OBJECTS to Companion::queryClosestObjects,
        VECTOR_DB_QUERY_CLOSEST_OBJECTS_DISTANCE to Companion::queryClosestObjectsDistance,
    )
), PostchainContextAware {

    private var chainId: Long? = null

    companion object : KLogging() {
        fun queryClosestObjects(moduleContext: VectorDbGTXModuleContext, ctx: EContext, argsGtv: Gtv): Gtv {
            val (vectorResult, queryTemplateType) = parseAndQueryClosestObjectsWithDistance(moduleContext, ctx, argsGtv as GtvDictionary)
            val ids = gtv(vectorResult.asArray().mapNotNull { resultRow -> resultRow["id"] })

            return if (queryTemplateType == null) {
                ids
            } else {
                moduleContext.module.query(ctx, queryTemplateType, gtv(mapOf("ids" to ids)))
            }
        }

        fun queryClosestObjectsDistance(moduleContext: VectorDbGTXModuleContext, ctx: EContext, argsGtv: Gtv): Gtv {
            val (vectorResult, queryTemplateType) = parseAndQueryClosestObjectsWithDistance(moduleContext, ctx, argsGtv as GtvDictionary)
            val idDistances = vectorResult.asArray()
                    .associate { resultRow -> resultRow["id"]!! to resultRow["distance"]!! }

            if (queryTemplateType == null) {
                return vectorResult
            } else {
                val rellQueryResult = moduleContext.module.query(ctx, queryTemplateType,
                        gtv(mapOf("ids" to gtv(idDistances.keys.toList()))))

                return gtv(rellQueryResult.asArray().map {
                    val id = it[0]
                    val distance = idDistances[id] ?: GtvNull
                    gtv(mapOf(
                            "value" to it[1],
                            "distance" to distance
                    ))
                })
            }
        }

        private fun parseAndQueryClosestObjectsWithDistance(moduleContext: VectorDbGTXModuleContext, ctx: EContext, args: GtvDictionary): Pair<GtvArray, String?> {
            val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
            val vectorQuery = args["q_vector"]?.asString() ?: throw UserMistake("No q_vector argument supplied")
            val maxDistance = BigDecimal(args["max_distance"]?.asString() ?: throw UserMistake("No max_distance argument supplied"))
            val maxVectors = args["max_vectors"]?.asInteger() ?: throw UserMistake("No max_vectors argument supplied")
            val queryTemplate = args["query_template"]?.asDict()
            val queryTemplateType = queryTemplate?.get("type")?.asString()

            return moduleContext.databaseOperations.queryClosestObjects(ctx, context, vectorQuery, maxDistance, maxVectors) to queryTemplateType
        }
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        conf.module = (configuration as GTXModuleAware).module

        val vectorDbConfig = configuration.rawConfig["vector_db_extension"]?.toObject<VectorDbConfig>()
                ?: throw UserMistake("No vector db extension config present")

        if (chainId != null) {

            val ctx = postchainContext.blockBuilderStorage.openWriteConnection(chainId!!)
            try{
                databaseOperations.initialize(ctx, vectorDbConfig)
            } finally {
                postchainContext.blockBuilderStorage.closeWriteConnection(ctx, true)
            }
        }
    }

    override fun initializeDB(ctx: EContext) {
        chainId = ctx.chainID
    }

    override fun getSpecialTxExtensions() = emptyList<GTXSpecialTxExtension>()

    override fun makeBlockBuilderExtensions(): List<BaseBlockBuilderExtension>  {
        return listOf(VectorDbEventProcessor(databaseOperations))
    }
}
