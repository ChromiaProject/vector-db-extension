package net.postchain.gtx.extensions.vectordb.helpers

import net.postchain.PostchainContext
import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.TxEventSink
import net.postchain.base.data.BaseBlockBuilder
import net.postchain.core.BlockEContext
import net.postchain.core.BlockchainConfiguration
import net.postchain.gtx.extensions.vectordb.VectorDbEventProcessor
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule

class VectorDbTestExceptionCaptorGTXModule : VectorDbGTXModule() {

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

    override fun makeBlockBuilderExtensions(): List<BaseBlockBuilderExtension> {
        return listOf(VectorDbTestExceptionCaptorEventProcessor(super.makeBlockBuilderExtensions()[0] as VectorDbEventProcessor))
    }
}

class VectorDbTestExceptionCaptorEventProcessor(val eventProcessor: VectorDbEventProcessor) : BaseBlockBuilderExtension by eventProcessor, TxEventSink by eventProcessor {

    companion object {
        var INIT_EXCEPTION: Exception? = null
    }

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        try {
            eventProcessor.init(blockEContext, baseBB)
        } catch (e: Exception) {
            INIT_EXCEPTION = e
        }
    }
}