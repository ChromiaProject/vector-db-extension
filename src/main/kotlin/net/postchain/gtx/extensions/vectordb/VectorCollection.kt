package net.postchain.gtx.extensions.vectordb

import net.postchain.gtx.extensions.vectordb.config.VectorDBIndex
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig

data class VectorCollection(
        val id: Long,
        val name: String,
        val dimensions: Long,
        val maxVectors: Long,
        val storeBatchSize: Long,
        val index: VectorDBIndex,
) {
    constructor(id: Long, name: String, config: VectorDbCollectionConfig) : this(
            id, name, config.dimensions, config.queryMaxVectors, config.storeBatchSize, config.indexType
    )
}