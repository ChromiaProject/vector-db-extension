package net.postchain.gtx.extensions.vectordb

import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig

/** Represents a collection of vectors */
data class VectorCollection(
        /** A ID of this collection, which mappens against a table in DB */
        val id: Long,

        /** Name of the collection */
        val name: String,

        /** This collections dimensions size */
        val dimensions: Long,

        /** The maximum number of vectors that can be queried from this collection */
        val queryMaxVectors: Long,

        /** The insert batch size used when storing vectors in batches */
        val storeBatchSize: Long,

        /** The type of index used for this collection */
        val index: VectorDBIndex,

        /** The origin of this collection, either defined in blockchain configuraiton or created dynamically by dapp */
        val origin: VectorCollectionOrigin,

        /** Whether this collection exists in the database or not. Deleted collections are still of interest for snapshot IDs etc. */
        val exists: Boolean
) {
    constructor(id: Long, name: String, config: VectorDbCollectionConfig, origin: VectorCollectionOrigin) : this(
            id, name, config.dimensions, config.queryMaxVectors, config.storeBatchSize, config.indexType, origin, true
    )
}
