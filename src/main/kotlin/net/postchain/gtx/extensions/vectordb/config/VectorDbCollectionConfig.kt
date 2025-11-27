package net.postchain.gtx.extensions.vectordb.config

import net.postchain.common.exception.UserMistake
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtx.extensions.vectordb.VectorDBIndex

data class VectorDbCollectionConfig(
        /** Number of dimensions of the vectors */
        @param:Name("dimensions")
        val dimensions: Long,

        /** The limit of the query_max_vectors query parameter */
        @param:Name("query_max_vectors")
        @param:DefaultValue(defaultLong = 10L)
        val queryMaxVectors: Long,

        /** Database batch insert size when storing vectors. This is used while inserting the data into the
         * vector DB, it does not limit the actual list of vectors sent to be stored. */
        @param:Name("store_batch_size")
        @param:DefaultValue(defaultLong = 300L)
        val storeBatchSize: Long,

        @param:Name("index")
        @param:DefaultValue(defaultString = "hnsw_cosine")
        val indexString: String,
) {
    val indexType: VectorDBIndex by lazy {
        try {
            VectorDBIndex.valueOf(indexString.uppercase())
        } catch (_: IllegalArgumentException) {
            throw UserMistake("Invalid index type: ${indexString}. Valid types are: ${VectorDBIndex.entries.joinToString(", ") { it.name.lowercase() }}")
        }
    }

    fun validate() {
        if (dimensions < 1) {
            throw IllegalArgumentException("dimensions must be at least 1")
        }
        if (queryMaxVectors < 1) {
            throw IllegalArgumentException("query_max_vectors must be at least 1")
        }
        if (storeBatchSize < 1) {
            throw IllegalArgumentException("store_batch_size must be at least 1")
        }

        indexType
    }
}