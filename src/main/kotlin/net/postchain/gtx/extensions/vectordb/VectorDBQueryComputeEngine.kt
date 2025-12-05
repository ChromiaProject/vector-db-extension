package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.concurrent.util.get
import net.postchain.core.BlockEContext
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.core.block.BlockQueries
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.toList
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.CompositeGTXModule
import net.postchain.gtx.GTXModuleAware
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_EXTENSION_CONFIG_NAME
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_QUERY_ARG_QUERY_TEMPLATE
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_QUERY_CLOSEST_OBJECTS
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbQueryComputeConfig
import net.postchain.gtx.extensions.vectordb.lib.vector_db_query_compute.QueryRequest
import net.postchain.gtx.extensions.vectordb.lib.vector_db_query_compute.QueryResultObject
import net.postchain.hybridcompute.DatabaseAwareHybridComputeEngine
import net.postchain.hybridcompute.HybridComputeEngine
import java.time.Duration
import kotlin.math.abs

class VectorDBQueryComputeEngine(
        private val dba: VectorDbDatabaseAccess = VectorDbDatabaseAccess()
) : HybridComputeEngine, DatabaseAwareHybridComputeEngine, PostchainContextAware {

    companion object : KLogging() {
        const val DISTANCE_EPSILON = 1e-6
    }

    override val name = "vector-db-query"

    private lateinit var configuration: BlockchainConfiguration
    private lateinit var postchainContext: PostchainContext
    private lateinit var blockQueries: BlockQueries
    private lateinit var queryTimeout: Duration

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext, ctx: EContext) {
        this.postchainContext = postchainContext
        this.configuration = configuration
        if (!configuration.hasQuery(VECTOR_DB_QUERY_CLOSEST_OBJECTS)) {
            throw UserMistake("Query $VECTOR_DB_QUERY_CLOSEST_OBJECTS not found, is the vector db module loaded?")
        }
        ((configuration as? GTXModuleAware)?.module as? CompositeGTXModule)?.modules
                ?.filterIsInstance<VectorDbGTXModule>()?.firstOrNull()
                ?: throw UserMistake("No VectorDB module found")
        val computeConfig = configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.toObject<VectorDbConfig>()
                ?.queryCompute ?: VectorDbQueryComputeConfig.DEFAULT_CONFIG
        queryTimeout = Duration.ofSeconds(computeConfig.timeoutSeconds)
    }

    override fun load(ctx: EContext) {
        blockQueries = postchainContext.blockQueriesProvider.getBlockQueries(configuration.blockchainRid)
                ?: throw ProgrammerMistake("Failed to get block queries for ${configuration.blockchainRid}")
    }

    override fun compute(ctx: EContext, input: Gtv): Pair<Gtv, Long> {
        if (input[VECTOR_DB_QUERY_ARG_QUERY_TEMPLATE] != null) {
            throw UserMistake("Query template is not allowed in query compute")
        }

        val result = blockQueries.queryWithTimeout(VECTOR_DB_QUERY_CLOSEST_OBJECTS, input,
                queryTimeout = queryTimeout, lockTimeout = queryTimeout).get()
        return result to 0
    }

    override fun validate(bctx: BlockEContext, input: Gtv, output: Gtv) {
        val queryRequest = input.toObject<QueryRequest>()
        val computedResult = output.toList<QueryResultObject>()
        val collection = dba.getExistingCollectionByName(bctx, queryRequest.collection)
                ?: throw UserMistake("Collection ${queryRequest.collection} not found")

        if (computedResult.isNotEmpty()) {
            if (computedResult.size > (queryRequest.queryMaxVectors ?: collection.queryMaxVectors)) {
                throw UserMistake("Query returned more results than allowed")
            }

            if (computedResult.any { it.distance.toBigDecimal() > queryRequest.maxDistance }) {
                throw UserMistake("Distance of some results exceeded max distance")
            }

            if (queryRequest.context != null && computedResult.any { it.context != queryRequest.context }) {
                throw UserMistake("Some results have a different context than requested")
            }

            val localResults = VectorDbDatabaseAccess.withTimeout(bctx, queryTimeout) {
                dba.getDistanceOfResults(bctx, collection, queryRequest.qVector, computedResult).toMutableList()
            }
            if (localResults.size < computedResult.size) {
                throw UserMistake("Failed to verify result. Missing local results.")
            }

            val nonExactHits = computedResult.filter { !localResults.remove(it) }
            val rejectedHits = nonExactHits.filterNot { computedHit ->
                localResults.removeIf { localHit ->
                    computedHit.id == localHit.id &&
                    computedHit.context == localHit.context &&
                            approximatelyEqual(computedHit.distance.toDouble(), localHit.distance.toDouble())
                }
            }

            logger.info { "Query validation results, exact match=${computedResult.size - nonExactHits.size}, " +
                    "approximate match=${nonExactHits.size - rejectedHits.size}, rejected=${rejectedHits.size}" }

            if (rejectedHits.isNotEmpty()) {
                throw UserMistake("Failed to verify result")
            }
        }
    }

    private fun approximatelyEqual(a: Double, b: Double, epsilon: Double = DISTANCE_EPSILON): Boolean {
        return abs(a - b) <= epsilon
    }

    override fun estimatePoints(input: Gtv): Long = 0

    override fun validate(input: Gtv, output: Gtv) {
        throw NotImplementedError()
    }
    override fun compute(input: Gtv): Pair<Gtv, Long> {
        throw NotImplementedError()
    }
    override fun load() {
        throw NotImplementedError()
    }
}