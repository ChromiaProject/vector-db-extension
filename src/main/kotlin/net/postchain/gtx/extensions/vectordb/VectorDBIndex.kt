package net.postchain.gtx.extensions.vectordb

enum class VectorDBIndex(
        /** Unique name suffix for table index name */
        val indexName: String,

        /** Vector embedding type */
        val indexEmbedding: String,

        /** Operator to use in queries */
        val operator: String
) {
    HNSW_COSINE("embedding_hnsw_index_cosine", "halfvec_cosine_ops", "<=>"), // vector_cosine_ops
    HNSW_L1("embedding_hnsw_index_l1", "halfvec_l1_ops", "<+>"), // vector_l1_ops
    HNSW_L2("embedding_hnsw_index_l2", "halfvec_l2_ops", "<->"), // vector_l2_ops
    HNSW_IP("embedding_hnsw_index_ip", "halfvec_ip_ops", "<#>"); // vector_ip_ops
}
