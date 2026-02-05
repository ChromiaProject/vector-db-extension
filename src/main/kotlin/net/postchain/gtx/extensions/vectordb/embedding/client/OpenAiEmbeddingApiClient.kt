package net.postchain.gtx.extensions.vectordb.embedding.client

import mu.KLogging
import net.postchain.api.rest.json.GtvJsonFactory.auto
import net.postchain.common.exception.UserMistake
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingComputeConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingNodeConfig
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingRequest
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.with

class OpenAiEmbeddingApiClient(
        embeddingNodeConfig: VectorDbEmbeddingNodeConfig,
        connectTimeoutMs: Long,
        computeConfig: VectorDbEmbeddingComputeConfig
) : BaseEmbeddingApiClient(embeddingNodeConfig, connectTimeoutMs, computeConfig) {

    companion object : KLogging()

    override fun buildRequest(request: EmbeddingRequest): Request {
        return Request(Method.POST, "${embeddingNodeConfig.url}/v1/embeddings")
                .with(openAiEmbeddingRequest of OpenAiEmbeddingRequest(
                        model = embeddingNodeConfig.model,
                        input = request.input
                ))
    }

    override fun processResponse(httpResponse: Response): EmbeddingResponse {
        val response = openAiEmbeddingResponse(httpResponse)
        if (response.model != embeddingNodeConfig.model) {
            throw UserMistake("Invalid model returned: ${response.model}")
        }
        if (response.data.isEmpty()) {
            throw UserMistake("No data found in response")
        }

        return EmbeddingResponse(response.data.map { EmbeddingResponseData(it.embedding) })
    }
}

val openAiEmbeddingRequest = Body.auto<OpenAiEmbeddingRequest>().toLens()
val openAiEmbeddingResponse = Body.auto<OpenAiEmbeddingResponse>().toLens()

data class OpenAiEmbeddingRequest(
        /**
         * The model to request embedding from.
         */
        val model: String,

        /**
         * The model input.
         */
        val input: List<String>,
)

data class OpenAiEmbeddingResponse(
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
        val data: List<OpenAiEmbeddingResponseData>,
)

data class OpenAiEmbeddingResponseData(
        val index: Long,
        val embedding: List<String>,
)
