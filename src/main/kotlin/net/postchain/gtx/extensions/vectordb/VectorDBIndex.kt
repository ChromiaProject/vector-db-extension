package net.postchain.gtx.extensions.vectordb

enum class VectorDBIndex(
        /** Unique name suffix for table index name */
        val indexName: String,

        /** Vector embedding type */
        val indexEmbedding: String,

        /** Operator to use in queries */
        val operator: String
) {
    HNSW_COSINE("hnsw_cosine_idx", "halfvec_cosine_ops", "<=>"), // vector_cosine_ops
    HNSW_L1("hnsw_l1_idx", "halfvec_l1_ops", "<+>"), // vector_l1_ops
    HNSW_L2("hnsw_l2_idx", "halfvec_l2_ops", "<->"), // vector_l2_ops
    HNSW_IP("hnsw_ip_idx", "halfvec_ip_ops", "<#>"); // vector_ip_ops
}