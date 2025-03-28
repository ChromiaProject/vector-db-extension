package net.postchain.gtx.extensions.vectordb

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class VectorDbConfig(
        /** Number of dimensions of the vectors */
        @Name("dimensions")
        val dimensions: Long,

        /** Number of dimensions of the vectors */
        @Name("max_vectors")
        @DefaultValue(defaultLong = 10L)
        val maxVectors: Long,
)