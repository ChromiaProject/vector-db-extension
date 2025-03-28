package net.postchain.gtx.extensions.vectordb

import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.TxEventSink
import net.postchain.base.data.BaseBlockBuilder
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockEContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary

const val EVENT_STORE_VECTOR_NAME = "store_vector"
const val EVENT_STORE_VECTORS_NAME = "store_vectors"
const val EVENT_DELETE_VECTOR_NAME = "delete_vector"
const val EVENT_DELETE_VECTORS_NAME = "delete_vectors"

class VectorDbEventProcessor(
        private val databaseOperations: VectorDbDatabaseOperations,
        private val vectorDbConfig: VectorDbConfig
) : BaseBlockBuilderExtension, TxEventSink {

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(EVENT_STORE_VECTOR_NAME, this)
        baseBB.installEventProcessor(EVENT_STORE_VECTORS_NAME, this)
        baseBB.installEventProcessor(EVENT_DELETE_VECTOR_NAME, this)
        baseBB.installEventProcessor(EVENT_DELETE_VECTORS_NAME, this)
    }

    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
        when (type) {
            EVENT_STORE_VECTOR_NAME -> storeVectorEvent(ctxt, data.asDict())
            EVENT_STORE_VECTORS_NAME -> storeVectorsEvent(ctxt, data.asDict())
            EVENT_DELETE_VECTOR_NAME -> deleteVectorEvent(ctxt, data as GtvDictionary)
            EVENT_DELETE_VECTORS_NAME -> deleteVectorsEvent(ctxt, data as GtvDictionary)
            else -> throw ProgrammerMistake("Unrecognized event")
        }
    }

    private fun storeVectorEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
        val vector = args["vector"]?.asString() ?: throw UserMistake("No vector argument supplied")
        val id = args["id"]?.asInteger() ?: throw UserMistake("No id argument supplied")

        databaseOperations.storeVectors(ctxt, context, listOf(vector to id))
    }

    private fun storeVectorsEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
        val vectors = args["vectors"]?.asArray()?.map {
            val vector = it["vector"]?.asString() ?: throw UserMistake("No vector argument supplied")
            val id = it["id"]?.asInteger() ?: throw UserMistake("No id argument supplied")
            vector to id
        } ?: throw UserMistake("No vectors argument supplied")

        databaseOperations.storeVectors(ctxt, context, vectors, vectorDbConfig.storeBatchSize)
    }

    private fun deleteVectorEvent(ctxt: TxEContext, args: GtvDictionary) {
        val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
        val id = args["id"]?.asInteger() ?: throw UserMistake("No id argument supplied")

        databaseOperations.deleteVectors(ctxt, context, listOf(id))
    }

    private fun deleteVectorsEvent(ctxt: TxEContext, args: GtvDictionary) {
        val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
        val ids = args["ids"]?.asArray() ?: throw UserMistake("No ids argument supplied")

        databaseOperations.deleteVectors(ctxt, context, ids.map { it.asInteger() })
    }

    override fun finalize(): Map<String, Gtv> = emptyMap()
}