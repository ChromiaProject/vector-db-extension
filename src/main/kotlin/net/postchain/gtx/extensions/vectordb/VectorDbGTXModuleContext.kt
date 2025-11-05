package net.postchain.gtx.extensions.vectordb

import net.postchain.PostchainContext
import net.postchain.gtx.GTXModule
import net.postchain.gtx.SnapshotContext
import net.postchain.gtx.extensions.vectordb.config.VectorCollectionOrigin
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import java.util.concurrent.ConcurrentMap

class VectorDbGTXModuleContext(
        val databaseOperations: VectorDbDatabaseAccess,
) {
    var snapshotContext: SnapshotContext? = null
    lateinit var module: GTXModule
    lateinit var collectionsByName: ConcurrentMap<String, VectorCollection>
    lateinit var postchainContext: PostchainContext
    lateinit var vectorDbConfig: VectorDbConfig
    lateinit var collectionOriginMode: VectorCollectionOrigin
    var emitCollections = false


    fun isInitialized(): Boolean {
        return this::module.isInitialized &&
                this::collectionsByName.isInitialized &&
                this::postchainContext.isInitialized &&
                this::vectorDbConfig.isInitialized &&
                this::collectionOriginMode.isInitialized
    }

    fun addCollection(collection: VectorCollection) {
        collectionsByName[collection.name] = collection
    }

    fun deleteCollectionByName(name: String) {
        collectionsByName.remove(name)
    }

    fun updateCollection(name: String, collection: VectorCollection) {
        collectionsByName[name] = collection
    }

    fun dynamicCollectionsEnabled(): Boolean {
        return collectionOriginMode == VectorCollectionOrigin.DYNAMIC
    }
}