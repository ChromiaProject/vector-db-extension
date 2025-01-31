package net.postchain.vectordb

import net.postchain.gtv.mapper.Name

data class VectorDbConfig(
        /** Number of dimensions of the vectors */
        @Name("dimensions")
        val dimensions: Long,
)