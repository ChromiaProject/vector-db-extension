package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingComputeConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingNodeConfig
import net.postchain.gtx.extensions.vectordb.embedding.client.EmbeddingAPIClient
import net.postchain.gtx.extensions.vectordb.embedding.client.EmbeddingApiType
import net.postchain.gtx.extensions.vectordb.embedding.client.GcpEmbeddingApiClient
import net.postchain.gtx.extensions.vectordb.embedding.client.OpenAiEmbeddingApiClient
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingRequest
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingResponse
import net.postchain.hybridcompute.HybridComputeEngine

class VectorDBEmbeddingComputeEngine(
        val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : HybridComputeEngine, PostchainContextAware {

    companion object : KLogging() {
        const val X_API_KEY_HEADER = "X-API-Key"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_TIMEOUT_SECONDS = 3L

        const val BASE_REQUEST_COST = 1000L

        const val EMBEDDING_VALIDATION_COSINE_DISTANCE_EPSILON = 0.001
    }

    override val name = "vector-db-embedding"

    private lateinit var embeddingNodeConfig: VectorDbEmbeddingNodeConfig
    private lateinit var computeConfig: VectorDbEmbeddingComputeConfig
    private lateinit var client: EmbeddingAPIClient

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext, ctx: EContext) {
        computeConfig = configuration.rawConfig[VectorDbGTXModule.VECTOR_DB_EXTENSION_CONFIG_NAME]?.toObject<VectorDbConfig>()
                ?.embeddingCompute ?: throw UserMistake("No embedding compute config found in the vector db config")
        embeddingNodeConfig = VectorDbEmbeddingNodeConfig.fromAppConfig(postchainContext.appConfig, computeConfig.model)
        client = createEmbeddingClient(embeddingNodeConfig, connectTimeoutMs, computeConfig)
    }

    override fun estimatePoints(input: Gtv): Long = getCost(input)

    override fun compute(input: Gtv): Pair<Gtv, Long> {
        val request = input.toObject<EmbeddingRequest>()
        val embeddings = client.requestEmbeddings(request)

        if (request.input.size != embeddings.data.size) {
            throw UserMistake("Requested ${request.input.size} embeddings, but got ${embeddings.data.size}")
        }

        return GtvObjectMapper.toGtvDictionary(EmbeddingResponse(embeddings.dataAsStringVectors())) to getCost(input)
    }

    override fun validate(input: Gtv, output: Gtv) {
        val request = input.toObject<EmbeddingRequest>()
        val validationResponse = client.requestEmbeddings(request)
        val validationOutput = EmbeddingResponse(validationResponse.data.map { it.embedding.joinToString(",", "[", "]") })
        val computeOutput = GtvObjectMapper.fromGtv(output, EmbeddingResponse::class.java)

        if (validationOutput.embeddings.size != computeOutput.embeddings.size) {
            throw UserMistake("Validation contains ${validationOutput.embeddings.size} embeddings, but compute contains ${computeOutput.embeddings.size}")
        }

        if (validationOutput != computeOutput) {
            val validationEmbeddings = validationResponse.data.map { embedding -> embedding.embedding.map { it.toDouble() }.toDoubleArray() }
            val computedEmbeddings = computeOutput.embeddings.map { embedding -> embedding.vectorToList().map { it.toDouble() }.toDoubleArray() }
            val similar = VectorSimilarity.areAllSimilar(validationEmbeddings, computedEmbeddings, EMBEDDING_VALIDATION_COSINE_DISTANCE_EPSILON)
            if (!similar.isSimilar) {
                throw UserMistake("Embeddings do not match, failed on similarity: ${similar.similarity}")
            }
        }
    }

    private fun createEmbeddingClient(embeddingNodeConfig: VectorDbEmbeddingNodeConfig, connectTimeoutMs: Long, computeConfig: VectorDbEmbeddingComputeConfig): EmbeddingAPIClient {
        return when (embeddingNodeConfig.apiType) {
            EmbeddingApiType.OPENAI -> OpenAiEmbeddingApiClient(embeddingNodeConfig, connectTimeoutMs, computeConfig)
            EmbeddingApiType.GCP -> GcpEmbeddingApiClient(embeddingNodeConfig, connectTimeoutMs, computeConfig)
        }
    }

    private fun getCost(input: Gtv): Long = BASE_REQUEST_COST + input.nrOfBytes()

    override fun load() {
    }
}