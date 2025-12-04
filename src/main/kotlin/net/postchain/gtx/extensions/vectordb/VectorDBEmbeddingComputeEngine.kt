package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.api.rest.json.GtvJsonFactory.auto
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.PostchainContextAware
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_EXTENSION_CONFIG_NAME
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingComputeConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbNodeVLLMConfig
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingRequest
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingResponse
import net.postchain.hybridcompute.HybridComputeEngine
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.http4k.client.ApacheClient
import org.http4k.core.Body
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ClientFilters
import org.http4k.filter.GzipCompressionMode
import org.http4k.lens.basicAuthentication
import java.math.BigDecimal
import org.http4k.core.Request as HttpRequest

class VectorDBEmbeddingComputeEngine(
        val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        private var vectorSimilarity: VectorSimilarity = VectorSimilarity()
) : HybridComputeEngine, PostchainContextAware {

    companion object : KLogging() {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_TIMEOUT_SECONDS = 3L

        const val BASE_REQUEST_COST = 1000L

        const val DISTANCE_EPSILON = 0.0004
    }

    override val name = "vector-db-embedding"

    private lateinit var nodeVLLMConfig: VectorDbNodeVLLMConfig
    private lateinit var computeConfig: VectorDbEmbeddingComputeConfig
    internal lateinit var client: HttpHandler

    val addRequestHeaders = Filter { next ->
        { request ->
            if (nodeVLLMConfig.bearerToken != null) {
                next(request.header("Authorization", "Bearer ${nodeVLLMConfig.bearerToken}"))
            } else {
                next(request)
            }
        }
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext, ctx: EContext) {
        nodeVLLMConfig = VectorDbNodeVLLMConfig.fromAppConfig(postchainContext.appConfig)
        computeConfig = configuration.rawConfig[VECTOR_DB_EXTENSION_CONFIG_NAME]?.toObject<VectorDbConfig>()
                ?.embeddingCompute ?: throw UserMistake("No embedding compute config found in the vector db config")

        client = addRequestHeaders
                .then(ClientFilters.AcceptGZip(GzipCompressionMode.Streaming())
                .then(
                        ClientFilters.RequestTracing(
                                startReportFn = { request, _ ->
                                    logger.debug { "\n$request" }
                                },
                                endReportFn = { _, response, _ ->
                                    logger.debug { "\n$response" }
                                }
                        ).then(
                                ApacheClient(HttpClients.custom()
                                        .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                                                .setDefaultConnectionConfig(ConnectionConfig.custom()
                                                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                                                        .build())
                                                .build())
                                        .setDefaultRequestConfig(
                                                RequestConfig.custom()
                                                        .setRedirectsEnabled(false)
                                                        .setCookieSpec(StandardCookieSpec.IGNORE)
                                                        .setResponseTimeout(Timeout.ofSeconds(computeConfig.timeoutSeconds))
                                                        .build())
                                        .build())
                        )))
    }

    override fun estimatePoints(input: Gtv): Long = getCost(input)

    override fun compute(input: Gtv): Pair<Gtv, Long> {
        val request = input.toObject<EmbeddingRequest>()
        val embeddings = requestEmbeddings(request)

        if (request.input.size != embeddings.data.size) {
            throw ProgrammerMistake("Requested ${request.input.size} embeddings, but got ${embeddings.data.size}")
        }

        return GtvObjectMapper.toGtvDictionary(EmbeddingResponse(embeddings.dataAsStringVectors())) to getCost(input)
    }

    override fun validate(input: Gtv, output: Gtv) {
        val request = input.toObject<EmbeddingRequest>()
        val validationResponse = requestEmbeddings(request)
        val validationOutput = EmbeddingResponse(validationResponse.data.map { it.embedding.joinToString(",", "[", "]") })
        val computeOutput = GtvObjectMapper.fromGtv(output, EmbeddingResponse::class.java)

        if (validationOutput.embeddings.size != computeOutput.embeddings.size) {
            throw ProgrammerMistake("Validation contains ${validationOutput.embeddings.size} embeddings, but compute contains ${computeOutput.embeddings.size}")
        }

        if (validationOutput != computeOutput) {
            val validationEmbeddings = validationResponse.data.map { embedding -> embedding.embedding.map { it.toDouble() }.toDoubleArray() }
            val computedEmbeddings = computeOutput.embeddings.map { embedding -> embedding.vectorToBigDecimalList().map { it.toDouble() }.toDoubleArray() }
            val similar = vectorSimilarity.areAllSimilar(validationEmbeddings, computedEmbeddings, DISTANCE_EPSILON)
            if (!similar.first) {
                throw ProgrammerMistake("Embeddings do not match, failed on similarity: ${similar.second}")
            }
        }
    }

    fun requestEmbeddings(request: EmbeddingRequest): VLLMEmbeddingResponse {
        val modelInput = request.input
        val httpResponse = client(HttpRequest(Method.POST, "${nodeVLLMConfig.url}/v1/embeddings")
                .with(vLLMEmbeddingRequest of VLLMEmbeddingRequest(
                        model = computeConfig.model,
                        input = modelInput
                )).let { if (nodeVLLMConfig.basicAuth != null) it.basicAuthentication(nodeVLLMConfig.basicAuth!!) else it })

        if (!httpResponse.status.successful) {
            throw ProgrammerMistake("Failed to request embedding: ${httpResponse.status} ${httpResponse.bodyString()}")
        }

        val response = vLLMEmbeddingResponse(httpResponse)
        if (response.model != computeConfig.model) {
            throw ProgrammerMistake("Invalid model returned: ${response.model}")
        }
        if (response.data.isEmpty()) {
            throw UserMistake("No data found in response")
        }

        logger.info("Generated id ${response.id} at ${response.created} with model ${response.model}")

        return response
    }

    private fun getCost(input: Gtv): Long = BASE_REQUEST_COST + input.nrOfBytes()

    override fun load() {
    }
}

val vLLMEmbeddingRequest = Body.auto<VLLMEmbeddingRequest>().toLens()
val vLLMEmbeddingResponse = Body.auto<VLLMEmbeddingResponse>().toLens()

data class VLLMEmbeddingRequest(
        /**
         * The model to request embedding from.
         */
        val model: String,

        /**
         * The model input.
         */
        val input: List<String>,
)

data class VLLMEmbeddingResponse(
        /**
         * A unique identifier for the chat completion response (e.g., `chatcmpl-verified-xxx`).
         */
        val id: String,

        /**
         * The Unix timestamp (in seconds) of when the chat completion was created.
         */
        val created: Long,

        /**
         * The model serving this embedding.
         */
        val model: String,

        /**
         * The model response data.
         */
        val data: List<VLLMEmbeddingResponseData>,
) {
    fun dataAsStringVectors(): List<String> {
        return data.map {
            it.embedding.joinToString(",", "[", "]")
        }
    }
}

data class VLLMEmbeddingResponseData(
        val index: Long,
        val embedding: List<BigDecimal>,
)
