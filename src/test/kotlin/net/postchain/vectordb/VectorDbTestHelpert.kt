package net.postchain.vectordb

import net.postchain.base.data.DatabaseAccess
import net.postchain.concurrent.util.get
import net.postchain.core.BlockchainEngine
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBody
import net.postchain.gtx.GtxOp
import net.postchain.vectordb.VectorDbDatabaseOperations.Companion.VECTOR_DB_COLUMN_CONTEXT
import net.postchain.vectordb.VectorDbDatabaseOperations.Companion.VECTOR_DB_COLUMN_EMBEDDING
import net.postchain.vectordb.VectorDbDatabaseOperations.Companion.VECTOR_DB_COLUMN_ID
import net.postchain.vectordb.VectorDbDatabaseOperations.Companion.VECTOR_DB_TABLE_STORED_VECTOR

fun getVectors(engine: BlockchainEngine, chainId: Long): List<Vector> {
    val ctx = engine.blockBuilderStorage.openReadConnection(chainId)
    try {
        DatabaseAccess.of(ctx).apply {
            val tableName = VectorDbDatabaseOperations.getChainTableName(DEFAULT_CHAIN_IID, VECTOR_DB_TABLE_STORED_VECTOR)
            val rs = ctx.conn.createStatement().executeQuery("SELECT $VECTOR_DB_COLUMN_CONTEXT, $VECTOR_DB_COLUMN_ID, $VECTOR_DB_COLUMN_EMBEDDING FROM \"$tableName\"")
            val vectors = mutableListOf<Vector>()
            while (rs.next()) {
                vectors.add(Vector(rs.getLong(1), rs.getLong(2), rs.getString(3)))
            }
            return vectors
        }
    } finally {
        engine.blockBuilderStorage.closeReadConnection(ctx)
    }
}

fun queryClosestObjects(engine: BlockchainEngine, context: Long, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateType: String? = null): List<String> {
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS, context, vector, maxDistance, maxVectors, queryTemplateType)
            .asArray().map { it.asString() }
}

fun queryClosestObjectsWithoutTemplate(engine: BlockchainEngine, context: Long, vector: String, maxDistance: Double, maxVectors: Long): List<Long> {
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS, context, vector, maxDistance, maxVectors, null)
            .asArray().map { it.asInteger() }
}

fun queryClosestObjectsDistance(engine: BlockchainEngine, context: Long, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateType: String? = null): List<Map<String, String?>> {
    val idOrValue = if (queryTemplateType == null) "id" else "value"
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS_DISTANCE, context, vector, maxDistance, maxVectors, queryTemplateType).asArray()
            .map { mapOf(
                    idOrValue to it[idOrValue]?.asString(),
                    "distance" to it["distance"]?.asString()
            )}
}

fun queryClosestObjectsDistanceWithoutTemplate(engine: BlockchainEngine, context: Long, vector: String, maxDistance: Double, maxVectors: Long): List<Map<String, Any>> {
    return queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS_DISTANCE, context, vector, maxDistance, maxVectors, null).asArray()
            .map { mapOf(
                    "id" to it["id"]!!.asInteger(),
                    "distance" to it["distance"]!!.asString()
            )}
}

private fun queryClosestObjects(engine: BlockchainEngine, queryName: String, context: Long, vector: String, maxDistance: Double, maxVectors: Long, queryTemplateType: String? = null): Gtv {
    val args = mutableListOf<Pair<String, Gtv>>(
            "context" to gtv(context),
            "q_vector" to gtv(vector),
            "max_distance" to gtv(maxDistance.toString()),
            "max_vectors" to gtv(maxVectors),
    )
    if (queryTemplateType != null) {
        args.add("query_template" to gtv(mapOf(
                "type" to gtv(queryTemplateType),
        )))
    }
    return engine.getBlockQueries().query(queryName, gtv(mapOf(*args.toTypedArray()))).get()
}

fun addMessage(engine: BlockchainEngine, message: String, vector: String) {
    val op = GtxOp("add_message", gtv(message), gtv(vector))
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

data class Vector(val context: Long, val id: Long, val embedding: String)
