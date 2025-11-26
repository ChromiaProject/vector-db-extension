package net.postchain.gtx.extensions.vectordb.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine

data class VectorDbEmbeddingComputeConfig(
        @param:Name("model")
        val model: String,

        @param:Name("timeout_seconds")
        @param:DefaultValue(defaultLong = VectorDBEmbeddingComputeEngine.DEFAULT_TIMEOUT_SECONDS)
        val timeoutSeconds: Long,
)
