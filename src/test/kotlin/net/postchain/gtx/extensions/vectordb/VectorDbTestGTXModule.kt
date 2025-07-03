package net.postchain.gtx.extensions.vectordb

import net.postchain.PostchainContext
import net.postchain.core.BlockchainConfiguration

class VectorDbTestGTXModule : VectorDbGTXModule() {

    companion object {
        var INIT_DB_EXCEPTION: Exception? = null
    }

    override fun initializeContext(configuration: BlockchainConfiguration, postchainContext: PostchainContext) {
        try {
            super.initializeContext(configuration, postchainContext)
        } catch (e: Exception) {
            INIT_DB_EXCEPTION = e
            throw e
        }
    }
}
