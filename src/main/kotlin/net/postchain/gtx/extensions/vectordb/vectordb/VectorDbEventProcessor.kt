package net.postchain.gtx.extensions.vectordb.vectordb

import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.TxEventSink
import net.postchain.base.data.BaseBlockBuilder
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockEContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvType

const val EVENT_STORE_VECTOR_NAME = "store_vector"
const val EVENT_DELETE_VECTOR_NAME = "delete_vector"

class VectorDbEventProcessor(
        private val databaseOperations: VectorDbDatabaseOperations
) : BaseBlockBuilderExtension, TxEventSink {

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(EVENT_STORE_VECTOR_NAME, this)
        baseBB.installEventProcessor(EVENT_DELETE_VECTOR_NAME, this)
    }

    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
        when (type) {
            EVENT_STORE_VECTOR_NAME -> storeVectorEvent(ctxt, data as GtvArray)
            EVENT_DELETE_VECTOR_NAME -> deleteVectorEvent(ctxt, data as GtvArray)
            else -> throw ProgrammerMistake("Unrecognized event")
        }
    }

    private fun storeVectorEvent(ctxt: TxEContext, data: GtvArray) {
        if (data.getSize() != 3) {
            throw UserMistake("Invalid number of arguments")
        }
        if (data[0].type != GtvType.INTEGER) {
            throw UserMistake("Invalid argument type for 'context'")
        }
        if (data[1].type != GtvType.STRING) {
            throw UserMistake("Invalid argument type for 'vector'")
        }
        if (data[2].type != GtvType.INTEGER) {
            throw UserMistake("Invalid argument type for 'id'")
        }
        val context = data[0].asInteger()
        val vector = data[1].asString()
        val id = data[2].asInteger()

        databaseOperations.storeVector(ctxt, id, context, vector)
    }

    private fun deleteVectorEvent(ctxt: TxEContext, data: GtvArray) {
        if (data.getSize() != 2) {
            throw UserMistake("Invalid number of arguments")
        }
        if (data[0].type != GtvType.INTEGER) {
            throw UserMistake("Invalid argument type for 'context'")
        }
        if (data[1].type != GtvType.INTEGER) {
            throw UserMistake("Invalid argument type for 'id'")
        }
        val context = data[0].asInteger()
        val id = data[1].asInteger()

        databaseOperations.deleteVector(ctxt, id, context)
    }

    override fun finalize(): Map<String, Gtv> = emptyMap()
}