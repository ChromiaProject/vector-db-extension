package net.postchain.gtx.extensions.vectordb.embedding.client

import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingRequest
import net.postchain.gtx.extensions.vectordb.listToVector

interface EmbeddingAPIClient {
    fun requestEmbeddings(request: EmbeddingRequest): EmbeddingResponse
}

data class EmbeddingResponse(
        /**
         * The model response data.
         */
        val data: List<EmbeddingResponseData>,
) {
    fun dataAsStringVectors(): List<String> {
        return data.map {
            it.embedding.listToVector()
        }
    }
}

data class EmbeddingResponseData(
        val embedding: List<String>,
)
