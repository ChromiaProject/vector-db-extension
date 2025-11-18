package net.postchain.gtx.extensions.vectordb

import mu.KLogging
import net.postchain.admin.cli.util.toHex
import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.TxEventSink
import net.postchain.base.data.BaseBlockBuilder
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockEContext
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.merkle.makeMerkleHashCalculator
import net.postchain.gtv.merkleHash
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseAccess.Vector
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_META_DATUM_ID
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.getActiveCollections
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig
import java.util.concurrent.ConcurrentHashMap

const val EVENT_STORE_VECTORS_NAME = "store_vectors"
const val EVENT_DELETE_VECTORS_NAME = "delete_vectors"
const val EVENT_CREATE_COLLECTION = "create_collection"
const val EVENT_DELETE_COLLECTION = "delete_collection"
const val EVENT_UPDATE_COLLECTION = "update_collection"

open class VectorDbEventProcessor(
        private val db: VectorDbDatabaseAccess,
        private val conf: VectorDbGTXModuleContext,
) : BaseBlockBuilderExtension, TxEventSink {

    companion object : KLogging()

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(EVENT_STORE_VECTORS_NAME, this)
        baseBB.installEventProcessor(EVENT_DELETE_VECTORS_NAME, this)
        baseBB.installEventProcessor(EVENT_CREATE_COLLECTION, this)
        baseBB.installEventProcessor(EVENT_DELETE_COLLECTION, this)
        baseBB.installEventProcessor(EVENT_UPDATE_COLLECTION, this)

        if (conf.refreshCollections) {
            val updatedCollections = getActiveCollections(db, blockEContext, conf.vectorDbConfig)
            blockEContext.addAfterCommitHook {
                conf.collectionsByName = ConcurrentHashMap(updatedCollections)
                conf.refreshCollections = false
            }

            emitCollections(blockEContext)
        }
    }

    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
        when (type) {
            EVENT_STORE_VECTORS_NAME -> storeVectorsEvent(ctxt, data.asDict())
            EVENT_DELETE_VECTORS_NAME -> deleteVectorsEvent(ctxt, data.asDict())

            EVENT_CREATE_COLLECTION-> createCollectionEvent(ctxt, data.asDict())
            EVENT_DELETE_COLLECTION -> deleteCollectionEvent(ctxt, data.asDict())
            EVENT_UPDATE_COLLECTION -> updateCollectionEvent(ctxt, data.asDict())

            else -> throw ProgrammerMistake("Unrecognized event")
        }
    }

    private fun storeVectorsEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val (collection, context) = parseCollectionAndContextArgs(ctxt, args)
        val vectors = args["vectors"]?.asArray()?.map {
            val vector = it["vector"]?.asString() ?: throw UserMistake("No vector argument supplied")
            val id = it["id"]?.asInteger() ?: throw UserMistake("No id argument supplied")
            vector to id
        } ?: throw UserMistake("No vectors argument supplied")

        val datumIds = db.getAvailableDatumIds(ctxt, vectors.size)
        val dbVectors = vectors.map { (vectorString, id) ->
            val vectorString = vectorString.split(",", "[", "]")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            if (vectorString.size.toLong() != collection.dimensions) {
                throw UserMistake("Vector $id has ${vectorString.size} dimensions, but the collection requires ${collection.dimensions} dimensions")
            }
            val compactVectorString = vectorString.joinToString(",", "[", "]")
            Vector(datumIds.pop(), context, id, compactVectorString)
        }
        db.storeVectors(ctxt, collection.id, dbVectors, collection.storeBatchSize)
        
        dbVectors.forEach {
            conf.snapshotContext?.let { snapshotContext ->
                logger.debug { "Emitting stored datum id ${it.datumId} in collection ${collection.id}: ${VectorDbDatumMapper.toVectorDatumGtv(collection.id, context, it.refId, it.vector).merkleHash(makeMerkleHashCalculator(2)).toHex()}: ${it.vector}" }
                snapshotContext.emitDatum(ctxt, it.datumId,
                        VectorDbDatumMapper.toVectorDatumGtv(collection.id, context, it.refId, it.vector), false)
            }
        }
    }

    private fun deleteVectorsEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val (collection, context) = parseCollectionAndContextArgs(ctxt, args)
        val ids = args["ids"]?.asArray() ?: throw UserMistake("No ids argument supplied")

        db.getDatumIdFromContextIds(ctxt, collection.id, context, ids.map { it.asInteger() }.toSet() )
                .forEach {
                    logger.debug { "Emitting deleted datum $it in collection ${collection.id}" }

                    conf.snapshotContext?.emitDatum(ctxt, it, VectorDbDatumMapper.toVectorDatumGtv(
                            collection.id, context, null, null), false)
                }

        db.deleteVectors(ctxt, collection.id, context, ids.map { it.asInteger() })
    }

    private fun createCollectionEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        checkDynamicCollectionsEnabled()

        val collection = args["collection"]?.asString() ?: throw UserMistake("No collection argument supplied")
        if (db.getExistingCollectionByName(ctxt, collection) != null) {
            throw UserMistake("Collection $collection already exists")
        }
        val dimensions = args["dimensions"]?.asInteger() ?: throw UserMistake("No dimensions argument supplied")
        val storeBatchSize = args["store_batch_size"]?.asInteger() ?: throw UserMistake("No store_batch_size argument supplied")
        val indexType = args["index_type"]?.asString() ?: throw UserMistake("No index_type argument supplied")
        val queryMaxVectors = args["query_max_vectors"]?.asInteger() ?: throw UserMistake("No query_max_vectors argument supplied")

        val tableConfig = VectorDbCollectionConfig(
                queryMaxVectors = queryMaxVectors,
                storeBatchSize = storeBatchSize,
                dimensions = dimensions,
                indexString = indexType
        ).apply { validate() }

        val createdCollection = db.createCollection(ctxt, collection, tableConfig)
        ctxt.addAfterAppendHook {
            conf.addCollection(createdCollection)
        }

        emitCollections(ctxt)
    }

    private fun deleteCollectionEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        checkDynamicCollectionsEnabled()

        val collection = parseCollectionArg(ctxt, args)
        db.deleteCollection(ctxt, collection)
        ctxt.addAfterAppendHook {
            conf.deleteCollectionByName(collection.name)
        }

        emitCollections(ctxt)
    }

    private fun updateCollectionEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        checkDynamicCollectionsEnabled()

        val collection = parseCollectionArg(ctxt, args)
        val storeBatchSize = args["store_batch_size"]?.let {
            if (it.isNull()) null else it.asInteger()
        }
        val queryMaxVectors = args["query_max_vectors"]?.let {
            if (it.isNull()) null else it.asInteger()
        }

        val updatedCollection = db.updateCollection(ctxt, collection, queryMaxVectors, storeBatchSize)
        ctxt.addAfterAppendHook {
            conf.updateCollection(collection.name, updatedCollection)
        }

        emitCollections(ctxt)
    }

    private fun emitCollections(ctx: BlockEContext) {
        conf.snapshotContext?.apply {
            val collections = db.getCollections(ctx)

            logger.debug { "Emitting collection metadata: $collections" }

            val metaDataGtv = VectorDbDatumMapper.toMetaDataGtv(collections)

            emitDatum(ctx, VECTOR_DB_META_DATUM_ID, metaDataGtv, false)
        }
    }

    private fun parseCollectionAndContextArgs(ctxt: TxEContext, args: Map<String, Gtv>): Pair<VectorCollection, Long> {
        val collection = parseCollectionArg(ctxt, args)
        val context = args["context"]?.asInteger() ?: throw UserMistake("No context argument supplied")
        return Pair(collection, context)
    }

    private fun parseCollectionArg(ctxt: TxEContext, args: Map<String, Gtv>): VectorCollection {
        val collectionName = args["collection"]?.asString() ?: throw UserMistake("No collection argument supplied")
        return db.getExistingCollectionByName(ctxt, collectionName) ?: throw UserMistake("Collection $collectionName not found")
    }

    private fun checkDynamicCollectionsEnabled() {
        if(!conf.dynamicCollectionsEnabled()) {
            throw UserMistake("Dynamic collection support is disabled in the configuration")
        }
    }

    @Suppress("removal")
    override fun finalize(): Map<String, Gtv> = emptyMap()
}
