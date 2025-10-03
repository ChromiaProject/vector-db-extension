package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.TxEventSink
import net.postchain.base.data.BaseBlockBuilder
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockEContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseAccess.Vector

const val EVENT_STORE_VECTORS_NAME = "store_vectors"
const val EVENT_DELETE_VECTORS_NAME = "delete_vectors"

class VectorDbEventProcessor(
        private val db: VectorDbDatabaseAccess,
        private val vectorContext: VectorDbGTXModuleContext
) : BaseBlockBuilderExtension, TxEventSink {

    companion object : KLogging() {
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }

    private var metaDataEmitted = false

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(EVENT_STORE_VECTORS_NAME, this)
        baseBB.installEventProcessor(EVENT_DELETE_VECTORS_NAME, this)

        vectorContext.snapshotContext?.let {
            if (!metaDataEmitted) {

                val collections = db.getCollections(blockEContext)

                logger.debug { "Emitting metadata: $collections" }

                val metaDataGtv = VectorDbDatumMapper.toMetaDataGtv(collections)
                vectorContext.snapshotContext?.emitDatum(blockEContext, VECTOR_DB_META_DATUM_ID, metaDataGtv, false)
                metaDataEmitted = true
            }
        }
    }

    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
        when (type) {
            EVENT_STORE_VECTORS_NAME -> storeVectorsEvent(ctxt, data.asDict())
            EVENT_DELETE_VECTORS_NAME -> deleteVectorsEvent(ctxt, data.asDict())
            else -> throw ProgrammerMistake("Unrecognized event")
        }
    }

    private fun storeVectorsEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val (collection, context) = parseCollectionAndContextArgs(args)
        val vectors = args["vectors"]?.asArray()?.map {
            val vector = it["vector"]?.asString() ?: throw UserMistake("No vector argument supplied")
            val id = it["id"]?.asInteger() ?: throw UserMistake("No id argument supplied")
            vector to id
        } ?: throw UserMistake("No vectors argument supplied")

        var datumIdSeq = db.getDatumIdSequenceOffset(ctxt)
        val dbVectors = vectors.map {
            Vector(datumIdSeq++, context, it.second, it.first.replace(WHITESPACE_REGEX, ""))
        }
        db.storeVectors(ctxt, collection.id, dbVectors, collection.storeBatchSize)
        db.setDatumIdSequenceOffset(ctxt, datumIdSeq)
        
        dbVectors.forEach {
            vectorContext.snapshotContext?.let { snapshotContext ->
                logger.debug { "Emitting stored datum id ${it.datumId} in collection ${collection.id}" }
                snapshotContext.emitDatum(ctxt, it.datumId,
                        VectorDbDatumMapper.toVectorDatumGtv(collection.id, context, it.refId, it.vector), false)
            }
        }
    }

    private fun deleteVectorsEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val (collection, context) = parseCollectionAndContextArgs(args)
        val ids = args["ids"]?.asArray() ?: throw UserMistake("No ids argument supplied")

        db.getDatumIdFromContextIds(ctxt, collection.id, context, ids.map { it.asInteger() }.toSet() )
                .forEach {
                    logger.debug { "Emitting deleted datum $it in collection ${collection.id}" }

                    vectorContext.snapshotContext?.emitDatum(ctxt, it, VectorDbDatumMapper.toVectorDatumGtv(
                            collection.id, context, null, null), false)
                }

        db.deleteVectors(ctxt, collection.id, context, ids.map { it.asInteger() })
    }

    private fun parseCollectionAndContextArgs(args: Map<String, Gtv>): Pair<VectorCollection, Long> {
        val collectionArg = args["collection"]?.asString() ?: throw UserMistake("No collection argument supplied")
        val collection = vectorContext.collectionsByName.getOrElse(collectionArg) { throw UserMistake("Collection $collectionArg not found") }
        val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
        return Pair(collection, context)
    }

    override fun finalize(): Map<String, Gtv> = emptyMap()
}

