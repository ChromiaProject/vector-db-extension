package net.postchain.gtx.extensions.vectordb.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine

data class VectorDbEmbeddingComputeConfig(
        /** The model name must be supported by the node */
        @param:Name("model")
        val model: String,

        /** The timeout for the embedding compute and validation request */
        @param:Name("timeout_seconds")
        @param:DefaultValue(defaultLong = VectorDBEmbeddingComputeEngine.DEFAULT_TIMEOUT_SECONDS)
        val timeoutSeconds: Long,
)
