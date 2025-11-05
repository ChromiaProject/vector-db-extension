package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import net.postchain.base.withWriteConnection
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VectorDBDatabaseAccessTest : IntegrationTestSetup() {

    private lateinit var dba: VectorDbDatabaseAccess
    private lateinit var node: PostchainTestNode

    init {
        configOverrides.setProperty("messaging.port", 0)
    }

    @BeforeEach
    fun beforeEach() {
        dba = VectorDbDatabaseAccess()
        node = createNodes(1, "/chains/vector_example_test_dynamic_collections.xml")[0]
    }

    @Test
    fun `test datum id sequence and reuse`() {
        withWriteConnection(node.getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine.blockBuilderStorage, DEFAULT_CHAIN_IID) { ctx ->

            dba.initializePgVector(ctx, node.appConfig.databaseSchema)

            listOf("collection1", "collection2", "collection3").forEach {
                dba.createCollection(ctx, it, VectorDbCollectionConfig(3,
                        10, 30, "hnsw_cosine"), node.appConfig.databaseSchema)
            }

            val collections = dba.getCollections(ctx).values.toList()
            assertThat(collections).hasSize(3)
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(1)

            // Store 3 datum
            dba.storeVectors(ctx, collections[0].id, listOf(VectorDbDatabaseAccess.Vector(1, 0, 0, "[0.1,0.2,0.3]")))
            dba.storeVectors(ctx, collections[1].id, listOf(VectorDbDatabaseAccess.Vector(2, 0, 1, "[0.1,0.2,0.3]")))
            dba.storeVectors(ctx, collections[2].id, listOf(VectorDbDatabaseAccess.Vector(3, 0, 2, "[0.1,0.2,0.3]")))

            // Next datum id start with 2
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(4)
            assertThat(dba.getAvailableDatumIds(ctx, 1)).isEqualTo(listOf(4L))

            // Delete last datum
            dba.deleteVectors(ctx, collections[2].id, 0, listOf(2))

            // Next in sequence is unchanged
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(4)

            // Next available is however now the datum we removed
            assertThat(dba.getAvailableDatumIds(ctx, 3)).isEqualTo(listOf(3L, 4L, 5L))

            // Delete datum 1 (ref id 0)
            dba.deleteVectors(ctx, collections[0].id, 0, listOf(0))

            // Next in sequence is unchanged
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(4)

            // Next available is however now the datum we removed
            assertThat(dba.getAvailableDatumIds(ctx, 3)).isEqualTo(listOf(1L, 3L, 4L))

            // Store 3 new datums
            dba.storeVectors(ctx, collections[0].id, listOf(VectorDbDatabaseAccess.Vector(1, 0, 0, "[0.1,0.2,0.3]")))
            dba.storeVectors(ctx, collections[1].id, listOf(VectorDbDatabaseAccess.Vector(3, 0, 1, "[0.1,0.2,0.3]")))
            dba.storeVectors(ctx, collections[2].id, listOf(VectorDbDatabaseAccess.Vector(4, 0, 2, "[0.1,0.2,0.3]")))

            // Next in sequence ticks up by 1 (3-2+3=4)
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(5)

            // Next available is however now the datum we removed
            assertThat(dba.getAvailableDatumIds(ctx, 3)).isEqualTo(listOf(5L, 6L, 7L))

            false
        }
    }
}