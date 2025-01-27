package net.postchain.vectordb

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.crypto.CryptoSystem
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension

class VectorDbSpecialTxExtension : GTXSpecialTxExtension {

    companion object : KLogging() {
    }

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        return emptyList()
    }

    override fun getRelevantOps() = emptySet<String>()

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition) = false

    override fun validateSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext, ops: List<OpData>): Boolean {
        return true
    }
}
