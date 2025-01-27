package net.postchain.vectordb

import net.postchain.core.EContext
import net.postchain.gtx.SimpleGTXModule

class VectorDbGTXModule : SimpleGTXModule<Unit>(
        Unit, mapOf(), mapOf()
) {

    override fun initializeDB(ctx: EContext) {}

    override fun getSpecialTxExtensions() = listOf(VectorDbSpecialTxExtension())
}
