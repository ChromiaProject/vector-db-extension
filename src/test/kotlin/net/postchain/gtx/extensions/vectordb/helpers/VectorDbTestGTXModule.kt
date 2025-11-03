package net.postchain.gtx.extensions.vectordb.helpers

import net.postchain.PostchainContext
import net.postchain.core.BlockchainConfiguration
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule

class VectorDbTestGTXModule : VectorDbGTXModule() {

    companion object {
        var INIT_EXCEPTION: Exception? = null
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        try {
            super.initializeContext(configuration, postchainContext)
        } catch (e: Exception) {
            INIT_EXCEPTION = e
            throw e
        }
    }
}