package net.postchain.gtx.extensions.vectordb

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class VectorDbConfig(
        /** Number of dimensions of the vectors */
        @Name("dimensions")
        val dimensions: Long,

        /** The limit of the max_vectors query parameter */
        @Name("max_vectors")
        @DefaultValue(defaultLong = 10L)
        val maxVectors: Long,

        /** Database batch insert size when storing vectors. This is used while inserting the data into the
         * vector DB, it does not limit the actual list of vectors sent to be stored. */
        @Name("store_batch_size")
        @DefaultValue(defaultLong = 300L)
        val storeBatchSize: Long,

        @Name("index")
        @DefaultValue(defaultString = "hnsw_cosine")
        val index: String,
)
