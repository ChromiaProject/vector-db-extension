package net.postchain.gtx.extensions.vectordb.helpers

import net.postchain.client.core.PostchainClient
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap
import net.postchain.concurrent.util.get
import net.postchain.core.BlockchainEngine
import net.postchain.core.Transaction
import net.postchain.devtools.PostchainTestNode
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBody
import net.postchain.gtx.GtxOp
import net.postchain.gtx.extensions.vectordb.VectorCollectionInfo
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseAccess
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_QUERY_CLOSEST_OBJECTS
import net.postchain.gtx.extensions.vectordb.VectorDBIndex

fun getVectors(engine: BlockchainEngine, chainId: Long, collection: String): List<Vector> {
    val ctx = engine.blockBuilderStorage.openReadConnection(chainId)
    try {
        VectorDbDatabaseAccess().apply {
            val collection = getExistingCollectionByName(ctx, collection)!!
            val tableId = collection.id
            val tableName = getCollectionTableName(ctx, tableId)
            return ctx.conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT ${VectorDbDatabaseAccess.COLLECTION_COLUMN_CONTEXT}, ${VectorDbDatabaseAccess.COLLECTION_COLUMN_ID}, ${VectorDbDatabaseAccess.COLLECTION_COLUMN_EMBEDDING} FROM $tableName").use { rs ->
                    val vectors = mutableListOf<Vector>()
                    while (rs.next()) {
                        vectors.add(Vector(rs.getLong(1), rs.getLong(2), rs.getString(3)))
                    }
                    vectors
                }
            }
        }
    } finally {
        engine.blockBuilderStorage.closeReadConnection(ctx)
    }
}

fun queryClosestObjectsGetStrings(engine: BlockchainEngine, collection: String, context: Long?, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateName: String? = null): List<String> {
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS, collection, context, vector, maxDistance, maxVectors, buildQueryTemplateOrNull(queryTemplateName))
            .asArray().map { it.asString() }
}

fun PostchainClient.queryClosestObjectsGetStrings(collection: String, context: Long?, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateName: String? = null): List<String> {
    return queryClosestObjects(this::query, VECTOR_DB_QUERY_CLOSEST_OBJECTS, collection, context, vector, maxDistance, maxVectors, buildQueryTemplateOrNull(queryTemplateName))
            .asArray().map { it.asString() }
}

fun queryClosestObjectsGetIdAndDistance(engine: BlockchainEngine, collection: String, context: Long, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateName: String? = null): List<Map<String, Any>> {
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS, collection, context, vector, maxDistance, maxVectors, buildQueryTemplateOrNull(queryTemplateName))
            .asArray()
            .map { mapOf(
                    "id" to it.asDict()["id"]!!.asInteger(),
                    "distance" to it.asDict()["distance"]!!.asString()
            )}
}

fun PostchainClient.queryClosestObjectsGetIdAndDistance(collection: String, context: Long, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateName: String? = null): List<Map<String, Any>> {
    return queryClosestObjects(this::query, VECTOR_DB_QUERY_CLOSEST_OBJECTS, collection, context, vector, maxDistance, maxVectors, buildQueryTemplateOrNull(queryTemplateName))
            .asArray()
            .map { mapOf(
                    "id" to it.asDict()["id"]!!.asInteger(),
                    "distance" to it.asDict()["distance"]!!.asString()
            )}
}

fun queryClosestObjectsGetTextAndDistance(engine: BlockchainEngine, collection: String, context: Long, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateName: String? = null): List<Map<String, String>> {
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS, collection, context, vector, maxDistance, maxVectors, buildQueryTemplateOrNull(queryTemplateName)).asArray()
            .map { mapOf(
                    "text" to it.asDict()["text"]!!.asString(),
                    "distance" to it.asDict()["distance"]!!.asString()
            )}
}

fun queryClosestObjectsNoTemplate(engine: BlockchainEngine, collection: String, context: Long?, vector: String, maxDistance: Double, maxVectors: Long): List<Triple<Long, Long, String>> {
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS, collection, context, vector, maxDistance, maxVectors, null).asArray()
            .map { Triple(
                    it.asDict()["context"]!!.asInteger(),
                    it.asDict()["id"]!!.asInteger(),
                    it.asDict()["distance"]!!.asString()
            )}
}

fun queryClosestObjects(engine: BlockchainEngine, queryName: String, collection: String, context: Long?, vector: String, maxDistance: Double, maxVectors: Long, queryTemplate: GtvDictionary? = null): Gtv {
    return queryClosestObjects(engine.getBlockQueries()::query, queryName, collection, context, vector, maxDistance, maxVectors, queryTemplate).get()
}

fun <T> queryClosestObjects(query: (String, Gtv) -> T, queryName: String, collection: String, context: Long?, vector: String, maxDistance: Double, maxVectors: Long, queryTemplate: GtvDictionary? = null): T {
    val args = mutableListOf<Pair<String, Gtv>>(
            "collection" to gtv(collection),
            "q_vector" to gtv(vector),
            "max_distance" to gtv(maxDistance.toString()),
            "query_max_vectors" to gtv(maxVectors),
    )
    if (context != null) {
        args.add("context" to gtv(context))
    }
    if (queryTemplate != null) {
        args.add("query_template" to queryTemplate)
    }
    return query(queryName, gtv(mapOf(*args.toTypedArray())))
}

fun getVectorCollections(engine: BlockchainEngine): List<VectorCollectionInfo> {
    val gtv = engine.getBlockQueries().query("get_vector_collections", gtv(mapOf())).get()
    return gtv.asArray().map {
        val dict = it.asDict()
        VectorCollectionInfo(
                name = dict["name"]!!.asString(),
                dimensions = dict["dimensions"]!!.asInteger(),
                index = VectorDBIndex.valueOf(dict["index"]!!.asString().uppercase()),
                maxVectors = dict["query_max_vectors"]!!.asInteger(),
                storeBatchSize =  dict["store_batch_size"]!!.asInteger()
        )
    }
}

fun buildQueryTemplateOrNull(name: String?, args: Gtv? = null): GtvDictionary? {
    if (name != null) {
        val dict: MutableMap<String, Gtv> = mutableMapOf(
                "name" to gtv(name),
        )
        if (args != null) {
            dict += mapOf("args" to args)
        }
        return gtv(dict)
    }
    return null
}

fun addMessage(engine: BlockchainEngine, message: String, vector: String): WrappedByteArray {
    val op = GtxOp("add_message", gtv(message), gtv(vector))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
    return tx.getRID().wrap()
}

fun addMessage(engine: BlockchainEngine, collection: String, message: String, vector: String): WrappedByteArray {
    val op = GtxOp("add_message_in_collection", gtv(collection), gtv(message), gtv(vector))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
    return tx.getRID().wrap()
}

fun addCollection(engine: BlockchainEngine, collection: String, dimensions: Long, indexType: VectorDBIndex, queryMaxVectors: Long, storeBatchSize: Long): WrappedByteArray {
    val op = GtxOp("add_collection", gtv(collection), gtv(dimensions), gtv(indexType.name), gtv(queryMaxVectors), gtv(storeBatchSize))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
    return tx.getRID().wrap()
}

fun changeCollection(engine: BlockchainEngine, collection: String, queryMaxVectors: Long?, storeBatchSize: Long?) {
    val gtvQueryMaxVectors = if(queryMaxVectors != null) gtv(queryMaxVectors) else GtvNull
    val gtvStoreBatchSize = if(storeBatchSize != null) gtv(storeBatchSize) else GtvNull

    val op = GtxOp("change_collection", gtv(collection), gtvQueryMaxVectors, gtvStoreBatchSize)
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
}

fun removeCollection(engine: BlockchainEngine, collection: String) {
    val op = GtxOp("remove_collection", gtv(collection))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
}

fun addMessages(engine: BlockchainEngine, messages: List<Pair<String, String>>) {
    val op = GtxOp("add_messages", gtv(messages.map {
        gtv(listOf(gtv(it.first), gtv(it.second)))
    }))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
}

fun addMessagesInCollection(engine: BlockchainEngine, collection: String, messages: List<Pair<String, String>>) {
    val op = GtxOp("add_messages_in_collection", gtv(collection), gtv(messages.map {
        gtv(listOf(gtv(it.first), gtv(it.second)))
    }))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
}

fun deleteMessage(engine: BlockchainEngine, message: String) {
    val op = GtxOp("delete_message", gtv(message))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
}

fun deleteMessages(engine: BlockchainEngine, message: List<String>) {
    val op = GtxOp("delete_messages", gtv(message.map { gtv(it) }))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
}

fun deleteMessagesInCollection(engine: BlockchainEngine, collection: String, message: List<String>) {
    val op = GtxOp("delete_messages_in_collection", gtv(collection), gtv(message.map { gtv(it) }))
    val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op), listOf()), listOf()).encode()
    )
    engine.getTransactionQueue().enqueue(tx)
}

data class Vector(val context: Long, val id: Long, val embedding: String)

fun PostchainTestNode.buildTransaction(op: GtxOp): Transaction {
    return buildTransaction(listOf(op))
}

fun PostchainTestNode.buildTransaction(ops: List<GtxOp>): Transaction {
    val engine = getBlockchainInstance().blockchainEngine
    return engine.getConfiguration().getTransactionFactory().decodeTransaction(
            Gtx(GtxBody(engine.getConfiguration().blockchainRid, ops, listOf()), listOf()).encode()
    )
}
