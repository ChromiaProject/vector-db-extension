package net.postchain.gtx.extensions.vectordb

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull

class VectorDbDatumMapper {
    companion object {
        fun toVectorDatumGtv(collectionId: Long, context: Long, refId: Long?, vector: String?, exclude: Boolean = false) =
                gtv(
                        gtv(collectionId),
                        gtv(context),
                        refId?.let { gtv(it) } ?: GtvNull,
                        vector?.let { gtv(it) } ?: GtvNull,
                        gtv(exclude)
                )

        fun fromVectorDatumGtv(gtv: Gtv): CollectionVectorDatum {
            val size = gtv.asArray().size
            return CollectionVectorDatum(
                    gtv[0].asInteger(),
                    gtv[1].asInteger(),
                    if (gtv[2].isNull()) null else gtv[2].asInteger(),
                    if (gtv[3].isNull()) null else gtv[3].asString(),
                    if (size < 5) false else gtv[4].asBoolean(),
            )
        }

        fun toMetaDataGtv(collections: List<VectorCollection>): GtvDictionary {
            return gtv(
                    "collections" to gtv(collections.sortedBy { it.id }.map {
                        gtv(
                                "id" to gtv(it.id),
                                "name" to gtv(it.name),
                                "dimensions" to gtv(it.dimensions),
                                "origin" to gtv(it.origin.name),
                                "index_type" to gtv(it.index.name),
                                "query_max_vectors" to gtv(it.queryMaxVectors),
                                "store_batch_size" to gtv(it.storeBatchSize),
                                "exists" to gtv(it.exists),
                        )
                    })
            )
        }

        fun fromMetaDataGtv(gtv: Gtv): Map<String, VectorCollection> {
            val collections = mutableMapOf<String, VectorCollection>()
            gtv["collections"]?.asArray()?.forEach {
                val name = it["name"]!!.asString()
                collections[name] = VectorCollection(
                        id = it["id"]!!.asInteger(),
                        dimensions = it["dimensions"]!!.asInteger(),
                        name = name,
                        origin = VectorCollectionOrigin.valueOf(it["origin"]!!.asString().uppercase()),
                        index = VectorDBIndex.valueOf(it["index_type"]!!.asString().uppercase()),
                        queryMaxVectors = it["query_max_vectors"]!!.asInteger(),
                        storeBatchSize = it["store_batch_size"]!!.asInteger(),
                        exists = it["exists"]!!.asBoolean(),
                )
            }
            return collections
        }
    }
}

data class CollectionVectorDatum(val collectionId: Long, val context: Long, val refId: Long?, val vector: String?, val exclude: Boolean)