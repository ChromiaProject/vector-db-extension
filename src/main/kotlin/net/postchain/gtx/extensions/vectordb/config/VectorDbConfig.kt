package net.postchain.gtx.extensions.vectordb.config

import net.postchain.gtv.mapper.DefaultEmpty
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable

/**
 * Top level configuration for vector database extension.
 */
data class VectorDbConfig(
        @param:Name("collections")
        @param:DefaultEmpty
        val collections: Map<String, VectorDbCollectionConfig> = emptyMap(),

        @param:Name("query_compute")
        @param:Nullable
        val queryCompute: VectorDbQueryComputeConfig? = null,

        @param:Name("embedding_compute")
        @param:Nullable
        val embeddingCompute: VectorDbEmbeddingComputeConfig? = null
) {
    companion object {
        val DEFAULT_CONFIG = VectorDbConfig(emptyMap())
    }
}