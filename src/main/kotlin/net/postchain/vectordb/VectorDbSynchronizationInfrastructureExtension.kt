package net.postchain.vectordb

import net.postchain.PostchainContext
import net.postchain.core.BlockchainProcess
import net.postchain.core.SynchronizationInfrastructureExtension

class VectorDbSynchronizationInfrastructureExtension(
        private val postchainContext: PostchainContext
) : SynchronizationInfrastructureExtension {

    override fun connectProcess(process: BlockchainProcess) {
    }

    override fun disconnectProcess(process: BlockchainProcess) {
    }

    override fun shutdown() {
    }
}
