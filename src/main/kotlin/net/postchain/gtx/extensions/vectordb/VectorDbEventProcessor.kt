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
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_META_DATUM_ID
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.getActiveCollections
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles events related to managing collection and vectors.
 */
open class VectorDbEventProcessor(
        private val db: VectorDbDatabaseAccess,
        private val conf: VectorDbGTXModuleContext,
) : BaseBlockBuilderExtension, TxEventSink {

    companion object : KLogging() {
        const val EVENT_STORE_VECTORS_NAME = "store_vectors"
        const val EVENT_EXCLUDE_VECTORS_NAME = "exclude_vectors"
        const val EVENT_DELETE_VECTORS_NAME = "delete_vectors"
        const val EVENT_CREATE_COLLECTION = "create_collection"
        const val EVENT_DELETE_COLLECTION = "delete_collection"
        const val EVENT_UPDATE_COLLECTION = "update_collection"

        const val COLLECTION_DIMENSIONS_ARG = "dimensions"
        const val COLLECTION_INDEX_TYPE_ARG = "index_type"
        const val COLLECTION_STORE_BATCH_SIZE_ARG = "store_batch_size"
        const val COLLECTION_QUERY_MAX_VECTORS_ARG = "query_max_vectors"
        const val COLLECTION_ARG = "collection"
    }

    override fun init(blockEContext: BlockEContext, baseBB: BaseBlockBuilder) {
        baseBB.installEventProcessor(EVENT_STORE_VECTORS_NAME, this)
        baseBB.installEventProcessor(EVENT_EXCLUDE_VECTORS_NAME, this)
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
            EVENT_EXCLUDE_VECTORS_NAME -> excludeVectorsEvent(ctxt, data.asDict())
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
            requireValidVector(vector, collection.dimensions)
            val id = it["id"]?.asInteger() ?: throw UserMistake("No id argument supplied")
            vector to id
        } ?: throw UserMistake("No vectors argument supplied")

        val datumIds = db.getAvailableDatumIds(ctxt, vectors.size)
        val dbVectors = vectors.map { (vectorString, id) ->
            val vectorList = vectorString.vectorToList()
            if (vectorList.size.toLong() != collection.dimensions) {
                throw UserMistake("Vector $id has ${vectorList.size} dimensions, but the collection requires ${collection.dimensions} dimensions")
            }
            val compactVectorString = vectorList.listToVector()
            Vector(datumIds.pop(), context, id, compactVectorString, false)
        }
        db.storeVectors(ctxt, collection.id, dbVectors, collection.storeBatchSize)
        
        dbVectors.forEach {
            conf.snapshotContext?.emitDatum(ctxt, it.datumId,
                    VectorDbDatumMapper.toVectorDatumGtv(collection.id, context, it.refId, it.vector), false)
        }
    }

    /** Exclude vectors are used to safely remove vectors from a collection without breaking any ongoing query
     * computation validatoin. Excluded vectors are exluded from query result */
    private fun excludeVectorsEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val (collection, context) = parseCollectionAndContextArgs(ctxt, args)
        val ids = parseIdsArg(args)

        db.excludeVectors(ctxt, collection.id, context, ids)
        db.getVectorsFromContextIds(ctxt, collection.id, context, ids.toSet())
                .forEach {
                    conf.snapshotContext?.emitDatum(ctxt, it.datumId, VectorDbDatumMapper.toVectorDatumGtv(
                            collection.id, context, it.refId, it.vector, it.exclude), false)
                }
    }

    private fun deleteVectorsEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        val (collection, context) = parseCollectionAndContextArgs(ctxt, args)
        val ids = parseIdsArg(args)

        db.getVectorsFromContextIds(ctxt, collection.id, context, ids )
                .map { it.datumId }
                .forEach {
                    logger.debug { "Emitting deleted datum $it in collection ${collection.id}" }

                    conf.snapshotContext?.emitDatum(ctxt, it, VectorDbDatumMapper.toVectorDatumGtv(
                            collection.id, context, null, null), false)
                }

        db.deleteVectors(ctxt, collection.id, context, ids)
    }

    private fun createCollectionEvent(ctxt: TxEContext, args: Map<String, Gtv>) {
        checkDynamicCollectionsEnabled()

        val collection = args[COLLECTION_ARG]?.asString() ?: throw UserMistake("No $COLLECTION_ARG argument supplied")
        if (db.getExistingCollectionByName(ctxt, collection) != null) {
            throw UserMistake("Collection $collection already exists")
        }
        val dimensions = args[COLLECTION_DIMENSIONS_ARG]?.asInteger()
                ?: throw UserMistake("No $COLLECTION_DIMENSIONS_ARG argument supplied")
        val storeBatchSize = args[COLLECTION_STORE_BATCH_SIZE_ARG]?.asInteger()
                ?: throw UserMistake("No $COLLECTION_STORE_BATCH_SIZE_ARG argument supplied")
        val indexType = args[COLLECTION_INDEX_TYPE_ARG]?.asString()
                ?: throw UserMistake("No $COLLECTION_INDEX_TYPE_ARG argument supplied")
        val queryMaxVectors = args[COLLECTION_QUERY_MAX_VECTORS_ARG]?.asInteger()
                ?: throw UserMistake("No $COLLECTION_QUERY_MAX_VECTORS_ARG argument supplied")

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
        val storeBatchSize = args[COLLECTION_STORE_BATCH_SIZE_ARG]?.let {
            if (it.isNull()) null else it.asInteger()
        }
        val queryMaxVectors = args[COLLECTION_QUERY_MAX_VECTORS_ARG]?.let {
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

    private fun parseIdsArg(args: Map<String, Gtv>): Set<Long> {
        val ids = args["ids"]?.asArray()?.map { it.asInteger() }?.toSet()
                ?: throw UserMistake("No ids argument supplied")
        if (ids.isEmpty()) {
            throw UserMistake("No ids supplied")
        }
        return ids
    }

    private fun parseCollectionArg(ctxt: TxEContext, args: Map<String, Gtv>): VectorCollection {
        val collectionName = args[COLLECTION_ARG]?.asString()
                ?: throw UserMistake("No $COLLECTION_ARG argument supplied")
        return db.getExistingCollectionByName(ctxt, collectionName)
                ?: throw UserMistake("Collection $collectionName not found")
    }

    private fun checkDynamicCollectionsEnabled() {
        if(!conf.dynamicCollectionsEnabled()) {
            throw UserMistake("Dynamic collection support is disabled in the configuration")
        }
    }

    @Suppress("removal")
    override fun finalize(): Map<String, Gtv> = emptyMap()
}
