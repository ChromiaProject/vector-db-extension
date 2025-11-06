package net.postchain.gtx.extensions.vectordb

import net.postchain.gtx.extensions.vectordb.config.VectorDBIndex
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig
import net.postchain.gtx.extensions.vectordb.config.VectorCollectionOrigin

data class VectorCollection(
        val id: Long,
        val name: String,
        val dimensions: Long,
        val queryMaxVectors: Long,
        val storeBatchSize: Long,
        val index: VectorDBIndex,
        val origin: VectorCollectionOrigin,
        val exists: Boolean
) {
    constructor(id: Long, name: String, config: VectorDbCollectionConfig, origin: VectorCollectionOrigin) : this(
            id, name, config.dimensions, config.queryMaxVectors, config.storeBatchSize, config.indexType, origin, true
    )
}

data class VectorCollectionInfo(
        val name: String,
        val dimensions: Long,
        val maxVectors: Long,
        val storeBatchSize: Long,
        val index: VectorDBIndex
)