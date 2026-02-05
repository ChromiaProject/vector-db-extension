package net.postchain.gtx.extensions.vectordb.embedding.client

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

class GcpEmbeddingApiClient(
        embeddingNodeConfig: VectorDbEmbeddingNodeConfig,
        connectTimeoutMs: Long,
        computeConfig: VectorDbEmbeddingComputeConfig
) : BaseEmbeddingApiClient(embeddingNodeConfig, connectTimeoutMs, computeConfig) {

    override fun buildRequest(request: EmbeddingRequest): Request {
        return Request(Method.POST, "${embeddingNodeConfig.url}/predict")
                .with(gcpEmbeddingRequest of GcpEmbeddingRequest(
                        request.input
                ))
    }

    override fun processResponse(httpResponse: Response): EmbeddingResponse {
        val response = gcpEmbeddingResponse(httpResponse)
        if (response.predictions.isEmpty()) {
            throw UserMistake("No data found in response")
        }

        return EmbeddingResponse(
                response.predictions.map { lists ->
                    EmbeddingResponseData(lists[0])
                })
    }
}

val gcpEmbeddingRequest = Body.auto<GcpEmbeddingRequest>().toLens()
val gcpEmbeddingResponse = Body.auto<GcpEmbeddingResponse>().toLens()

data class GcpEmbeddingRequest(
        val instances: List<String>,
)

data class GcpEmbeddingResponse(
        val deployedModelId: String,
        val model: String,
        val modelDisplayName: String,
        val modelVersionId: String,
        val predictions: List<List<List<String>>>,
)
