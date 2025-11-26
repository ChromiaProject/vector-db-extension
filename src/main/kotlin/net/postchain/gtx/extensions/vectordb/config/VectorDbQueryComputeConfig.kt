package net.postchain.gtx.extensions.vectordb.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

private const val QUERY_COMPUTE_DEFAULT_TIMEOUT_SECONDS = 3L

data class VectorDbQueryComputeConfig(
        @param:Name("timeout_seconds")
        @param:DefaultValue(defaultLong = QUERY_COMPUTE_DEFAULT_TIMEOUT_SECONDS)
        val timeoutSeconds: Long = QUERY_COMPUTE_DEFAULT_TIMEOUT_SECONDS,
) {
    companion object {
        val DEFAULT_CONFIG = VectorDbQueryComputeConfig()
    }
}
