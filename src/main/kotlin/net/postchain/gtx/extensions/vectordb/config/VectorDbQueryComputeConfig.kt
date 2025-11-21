package net.postchain.gtx.extensions.vectordb.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

private const val QUERY_COMPUTE_DEFAULT_TIMEOUT_MS = 3_000L

data class VectorDbQueryComputeConfig(
        @param:Name("timeout_ms")
        @param:DefaultValue(defaultLong = QUERY_COMPUTE_DEFAULT_TIMEOUT_MS)
        val timeoutMs: Long = QUERY_COMPUTE_DEFAULT_TIMEOUT_MS,
) {
    companion object {
        val DEFAULT_CONFIG = VectorDbQueryComputeConfig()
    }
}
