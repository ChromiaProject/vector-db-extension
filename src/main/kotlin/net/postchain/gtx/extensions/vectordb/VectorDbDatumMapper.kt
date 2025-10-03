package net.postchain.gtx.extensions.vectordb

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull

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

        fun toMetaDataGtv(collections: Map<String, Long>): GtvDictionary {
            return gtv(
                    "collections" to gtv(collections.entries.map { gtv(
                            "id" to gtv(it.value),
                            "name" to gtv(it.key)
                    ) })
            )
        }

        fun fromMetaDataGtv(gtv: Gtv): Map<String, Long> {
            val collections = mutableMapOf<String, Long>()
            gtv["collections"]?.asArray()?.forEach {
                collections[it["name"]!!.asString()] = it["id"]!!.asInteger()
            }
            return collections
        }
    }
}

data class CollectionVectorDatum(val collectionId: Long, val context: Long, val refId: Long?, val vector: String?)