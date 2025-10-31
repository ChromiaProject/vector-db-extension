package net.postchain.gtx.extensions.vectordb.config

import net.postchain.gtv.mapper.Name

data class VectorDbConfig(
        @Name("collections")
        val collections: Map<String, VectorDbCollectionConfig> = emptyMap()

        /** Possible container/db specific settings:
         *  SET hnsw.ef_search = 200;
         *  SET hnsw.iterative_scan = strict_order; + more with iterative scan
         */
) {

    companion object {
        val DEFAULT_CONFIG = VectorDbConfig()
    }
}