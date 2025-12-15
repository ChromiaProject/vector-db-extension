package net.postchain.gtx.extensions.vectordb

import net.postchain.api.rest.json.GtvJsonFactory.auto
import net.postchain.common.exception.UserMistake
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingRequest
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.with
import org.http4k.lens.basicAuthentication
import org.http4k.core.Request as HttpRequest

class VectorDBEmbeddingComputeEngineGCP(
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : VectorDBEmbeddingComputeEngine(connectTimeoutMs) {

    override fun requestEmbeddings(request: EmbeddingRequest): VLLMEmbeddingResponse {
        val modelInput = request.input
        val httpResponse = client(HttpRequest(Method.POST, "${embeddingNodeConfig.url}/predict")
                .with(gcpEmbeddingRequest of GCPEmbeddingRequest(
                        modelInput
                )).let { if (embeddingNodeConfig.basicAuth != null) it.basicAuthentication(embeddingNodeConfig.basicAuth!!) else it })

        if (!httpResponse.status.successful) {
            throw UserMistake("Failed to request embedding: ${httpResponse.status} ${httpResponse.bodyString()}")
        }

        val response = gcpEmbeddingResponse(httpResponse)
//        if (response.model != embeddingNodeConfig.model) {
//            throw UserMistake("Invalid model returned: ${response.model}")
//        }
        if (response.predictions.isEmpty()) {
            throw UserMistake("No data found in response")
        }

//        logger.info("Generated id ${response.id} at ${response.created} with model ${response.model}")

        return VLLMEmbeddingResponse("", 0, "",
                response.predictions.mapIndexed { index, lists ->
            VLLMEmbeddingResponseData(index.toLong(), lists[0])
        })
    }
}

val gcpEmbeddingRequest = Body.auto<GCPEmbeddingRequest>().toLens()
val gcpEmbeddingResponse = Body.auto<GCPEmbeddingResponse>().toLens()

data class GCPEmbeddingRequest(
    val instances: List<String>,
)

data class GCPEmbeddingResponse(
        val predictions: List<List<List<String>>>,
) {
    fun dataAsStringVectors(): List<String> {
        return predictions.map {
            it.joinToString(",", "[", "]")
        }
    }
}
