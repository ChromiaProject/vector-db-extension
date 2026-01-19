package net.postchain.gtx.extensions.vectordb

/**
 * Enumeration representing all supported vector database embedding types.
 */
enum class VectorDBIndex(
        /** Unique name suffix for table index name */
        val indexName: String,

        /** PGVector extension embedding type */
        val indexEmbedding: String,

        /** Operator to use in queries for this embedding type */
        val operator: String
) {
    HNSW_COSINE("hnsw_cosine_idx", "halfvec_cosine_ops", "<=>"),
    HNSW_L1("hnsw_l1_idx", "halfvec_l1_ops", "<+>"),
    HNSW_L2("hnsw_l2_idx", "halfvec_l2_ops", "<->"),
    HNSW_IP("hnsw_ip_idx", "halfvec_ip_ops", "<#>");
}