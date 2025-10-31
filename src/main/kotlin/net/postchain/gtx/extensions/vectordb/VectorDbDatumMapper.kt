package net.postchain.gtx.extensions.vectordb

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.extensions.vectordb.config.VectorDBIndex
import net.postchain.gtx.extensions.vectordb.config.VectorCollectionOrigin

class VectorDbDatumMapper {
    companion object {
        fun toVectorDatumGtv(collectionId: Long, context: Long, refId: Long?, vector: String?) =
                gtv(
                        gtv(collectionId),
                        gtv(context),
                        if (refId == null) GtvNull else gtv(refId),
                        if (vector == null) GtvNull else gtv(vector)
                )

        fun fromVectorDatumGtv(gtv: Gtv): CollectionVectorDatum {
            return CollectionVectorDatum(
                    gtv[0].asInteger(),
                    gtv[1].asInteger(),
                    if (gtv[2].isNull()) null else gtv[2].asInteger(),
                    if (gtv[3].isNull()) null else gtv[3].asString(),
            )
        }

        fun toMetaDataGtv(collections: Map<String, VectorCollection>): GtvDictionary {
            return gtv(
                    "collections" to gtv(collections.entries.map { gtv(
                            "id" to gtv(it.value.id),
                            "name" to gtv(it.key),
                            "dimensions" to gtv(it.value.dimensions),
                            "origin" to gtv(it.value.origin.name),
                            "index_type" to gtv(it.value.index.name),
                            "query_max_vectors" to gtv(it.value.maxVectors),
                            "store_batch_size" to gtv(it.value.storeBatchSize),
                    ) })
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
                        maxVectors = it["query_max_vectors"]!!.asInteger(),
                        storeBatchSize = it["store_batch_size"]!!.asInteger(),
                )
            }
            return collections
        }
    }
}

data class CollectionVectorDatum(val collectionId: Long, val context: Long, val refId: Long?, val vector: String?)