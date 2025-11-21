package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.concurrent.util.get
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.core.block.BlockQueries
import net.postchain.gtv.Gtv
import net.postchain.gtx.CompositeGTXModule
import net.postchain.gtx.GTXModuleAware
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_QUERY_ARG_QUERY_TEMPLATE
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_QUERY_CLOSEST_OBJECTS
import net.postchain.hybridcompute.HybridComputeEngine

class VectorDBQueryComputeEngine : HybridComputeEngine, PostchainContextAware {

    companion object : KLogging()

    override val name = "vector-db-query"

    private lateinit var configuration: BlockchainConfiguration
    private lateinit var postchainContext: PostchainContext
    private lateinit var blockQueries: BlockQueries

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext, ctx: EContext) {
        this.postchainContext = postchainContext
        this.configuration = configuration
        if (!configuration.hasQuery(VECTOR_DB_QUERY_CLOSEST_OBJECTS)) {
            throw UserMistake("Query $VECTOR_DB_QUERY_CLOSEST_OBJECTS not found, is the vector db module loaded?")
        }
        ((configuration as? GTXModuleAware)?.module as? CompositeGTXModule)?.modules
                ?.filterIsInstance<VectorDbGTXModule>()?.firstOrNull()
                ?: throw UserMistake("No VectorDB module found")

    }

    override fun load() {
        blockQueries = postchainContext.blockQueriesProvider.getBlockQueries(configuration.blockchainRid)
                ?: throw ProgrammerMistake("Failed to get block queries for ${configuration.blockchainRid}")
    }

    override fun compute(input: Gtv): Pair<Gtv, Long> {
        if (input[VECTOR_DB_QUERY_ARG_QUERY_TEMPLATE] != null) {
            throw UserMistake("Query template is not allowed in query compute")
        }

        val result = blockQueries.query(VECTOR_DB_QUERY_CLOSEST_OBJECTS, input).get()
        return result to 0
    }

    override fun validate(input: Gtv, output: Gtv) {
        val localResult = blockQueries.query(VECTOR_DB_QUERY_CLOSEST_OBJECTS, input).get()

        // TODO validate result
        if (localResult == output) {
            logger.info { "Validation of vector db query succeeded" }
        } else {
            logger.warn { "Validation of vector db query failed, but is ignored." }
        }
    }

    override fun estimatePoints(input: Gtv): Long = 0
}
