package net.postchain.gtx.extensions.vectordb

import assertk.Assert
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import net.postchain.base.withReadConnection
import net.postchain.concurrent.util.get
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.snapshot.SnapshotTestBase
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtx.GTXBlockchainConfigurationFactory
import net.postchain.gtx.GtxBuilder
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseAccess.Companion.REUSABLE_DATUM_ID_COLUMN_DATUM_ID
import net.postchain.gtx.extensions.vectordb.helpers.getVectors
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetStrings
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsNoTemplate
import net.postchain.test.modify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class VectorDbSnapshotIT : SnapshotTestBase() {

    @BeforeEach
    fun createExtension() {
        PGVectorBaseTest.ensureExtension()
    }

    /** With 4 nodes, create vectors and then do a clean restart of node 4 to make it sync snapshot. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun basicSync() {
        val messages = 30

        startManagedSystem(4, 0, restApi = true)

        val config = GtvMLParser.parseGtvML(Any::class::class.java.getResource("/chains/vector_example_test.xml")!!.readText())
                .modify(listOf("snapshot")) {
                    gtv("interval" to gtv(1))
                }
                .modify(listOf("features")) { configEntry ->
                    gtv(configEntry.asDict() + mapOf("snapshot_enabled" to gtv(true)))
                }
        startNewBlockchain(setOf(0, 1, 2, 3), setOf(), null, rawBlockchainConfiguration = GtvEncoder.encodeGtv(config), blockchainConfigurationFactory = GTXBlockchainConfigurationFactory())
        buildBlock(DEFAULT_CHAIN_IID)

        buildBlockWithOps(nodes) {
            (1..30).forEach { i ->
                it.addOperation("add_message", gtv("hello $i"), gtv("[0.1${i}, 0.2${i}, 0.3${i}]"))
            }
        }

        val engine = nodes[0].getBlockchainInstance().blockchainEngine
        val queryResults = queryClosestObjectsGetStrings(engine, "messages", 0, "[0.1, 0.2, 0.3]", 100.0, 1, "get_messages")

        assertThat(queryResults).hasSize(1)
        assertThat(queryResults[0]).isEqualTo("hello 1")
        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(messages)

        val height = nodes[0].blockQueries().getLastBlockHeight().get()
        assertThat(height).isEqualTo(1)

        // Assert that we could snapshot sync the chain on the replica node
        restartAndAwaitSnapshotSync(3, height)

        // Verify database content for vector module is identical
        assertThat(nodes[3]).hasIdenticalTableContentAs(nodes[0],
                basicChainTableContentProvider(listOf(
                        "sys.x.vectordb.collection_0",
                        "sys.x.vectordb.collection_meta",
                        "sys.x.vectordb.reusable_datum_id",
                )))

        // Verify queries return identical results on all nodes - we can't use query template here since Rell is not restored on node 3
        assertThat(nodes).hasSameState { node ->
            val engine = node.getBlockchainInstance().blockchainEngine
            queryClosestObjectsNoTemplate(engine, "messages", 0, "[0.1, 0.2, 0.3]", 100.0, 1)
        }
    }

    /** With 4 nodes, create and remove collections and vectors and sync snapshot. Make further changes
     *  to collections and vectors and verify datum ids are reused and generated as expected. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun sycnWithRemovedCollectionsAndDatumIdGaps() {
        val dba = VectorDbDatabaseAccess()
        val context = 0L
        var refSeq = 0L

        startManagedSystem(4, 0, restApi = true)

        val config = GtvMLParser.parseGtvML(Any::class::class.java.getResource("/chains/vector_only_test.xml")!!.readText())
                .modify(listOf("snapshot")) {
                    gtv("interval" to gtv(1))
                }
                .modify(listOf("features")) { configEntry ->
                    gtv(configEntry.asDict() + mapOf("snapshot_enabled" to gtv(true)))
                }
        startNewBlockchain(setOf(0, 1, 2, 3), setOf(), null, rawBlockchainConfiguration = GtvEncoder.encodeGtv(config), blockchainConfigurationFactory = GTXBlockchainConfigurationFactory())

        buildBlockWithOps(nodes) {
            it.addOperation("add_collection", gtv("collection_0"), gtv(3), gtv("hnsw_cosine"), gtv(10), gtv(300))
            it.addOperation("add_collection", gtv("collection_1"), gtv(3), gtv("hnsw_cosine"), gtv(10), gtv(300))
            it.addOperation("add_collection", gtv("collection_2"), gtv(3), gtv("hnsw_cosine"), gtv(10), gtv(300))
        }

        withReadConnection(nodes[0].postchainContext.sharedStorage, DEFAULT_CHAIN_IID) { ctx ->
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(1)
            assertThat(dba.getAvailableDatumIds(ctx, 3)).isEqualTo(listOf(1L, 2L, 3L))
        }

        // Add 3x10 messages
        buildBlockWithOps(nodes) {
            (0..2).forEach { cid ->
            it.addOperation("add_vectors", gtv("collection_$cid"), gtv(context), gtv((1L..10).map {
                    gtv(listOf(gtv("[0.1, 0.2, 0.3]"), gtv(refSeq++)))
                }))
            }
        }

        withReadConnection(nodes[0].postchainContext.sharedStorage, DEFAULT_CHAIN_IID) { ctx ->
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(31)
            assertThat(dba.getAvailableDatumIds(ctx, 3)).isEqualTo(listOf(31L, 32L, 33L))
        }

        // Remove one collection and some datums
        buildBlockWithOps(nodes) {
            it.addOperation("delete_collection", gtv("collection_1"))
            it.addOperation("delete_vectors", gtv("collection_0"), gtv(context), gtv(listOf(gtv(0), gtv(5))))
            it.addOperation("delete_vectors", gtv("collection_2"), gtv(context), gtv(listOf(gtv(28), gtv(29))))
        }

        withReadConnection(nodes[0].postchainContext.sharedStorage, DEFAULT_CHAIN_IID) { ctx ->
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(31)
            assertThat(dba.getAvailableDatumIds(ctx, 5)).isEqualTo(listOf(1L, 6L, 11L, 12L, 13L))
        }


        // Assert that we could snapshot sync the chain on the replica node
        val height = nodes[0].blockQueries().getLastBlockHeight().get()
        restartAndAwaitSnapshotSync(3, height)

        // Verify database content for vector module is identical
        assertThat(nodes[3]).hasIdenticalTableContentAs(nodes[0],
                basicChainTableContentProvider(listOf(
                        "sys.x.vectordb.collection_0",
                        "sys.x.vectordb.collection_2",
                        "sys.x.vectordb.collection_meta",
                )))
        assertThat(nodes[3]).hasIdenticalReusableTableContentAs(nodes[0])

        // Add new collections, id from collection_1 is not reused
        buildBlockWithOps(nodes) {
            it.addOperation("add_collection", gtv("collection_3"), gtv(3), gtv("hnsw_cosine"), gtv(10), gtv(300))
            it.addOperation("add_collection", gtv("collection_4"), gtv(3), gtv("hnsw_cosine"), gtv(10), gtv(300))
        }

        withReadConnection(nodes[0].postchainContext.sharedStorage, DEFAULT_CHAIN_IID) { ctx ->
            // Collection 3 & 4 should have received new ids
            assertThat(dba.getCollections(ctx).map { it.id }.toSet()).isEqualTo(setOf(0L, 2L, 3L, 4L))
        }

        // Add 5 new messages
        buildBlockWithOps(nodes) {
            it.addOperation("add_vectors", gtv("collection_3"), gtv(context), gtv((1L..5).map {
                gtv(listOf(gtv("[0.1, 0.2, 0.3]"), gtv(refSeq++)))
            }))
        }

        // 5 ids was reused, sequence start is unchanged
        withReadConnection(nodes[0].postchainContext.sharedStorage, DEFAULT_CHAIN_IID) { ctx ->
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(31)
            assertThat(dba.getAvailableDatumIds(ctx, 2)).isEqualTo(listOf(14L, 15L))
        }

        // Add 10 new messages
        buildBlockWithOps(nodes) {
            it.addOperation("add_vectors", gtv("collection_3"), gtv(context), gtv((1L..10).map {
                gtv(listOf(gtv("[0.1, 0.2, 0.3]"), gtv(refSeq++)))
            }))
        }

        // The 10 new vectors should have reused 9, and 1 from sequence
        withReadConnection(nodes[0].postchainContext.sharedStorage, DEFAULT_CHAIN_IID) { ctx ->
            assertThat(dba.getNextAvailableDatumIdFromSequence(ctx)).isEqualTo(32)
            assertThat(dba.getAvailableDatumIds(ctx, 2)).isEqualTo(listOf(32L, 33L))
        }

        // Verify database content for vector module is identical
        assertThat(nodes[3]).hasIdenticalTableContentAs(nodes[0],
                basicChainTableContentProvider(listOf(
                        "sys.x.vectordb.collection_0",
                        "sys.x.vectordb.collection_2",
                        "sys.x.vectordb.collection_3",
                        "sys.x.vectordb.collection_4",
                        "sys.x.vectordb.collection_meta",
                )))
        assertThat(nodes[3]).hasIdenticalReusableTableContentAs(nodes[0])

        assertThat(nodes).hasSameState {
            it.blockQueries(DEFAULT_CHAIN_IID).getLastBlockHeight().get()
        }
        assertThat(nodes).hasSameSnapshotRootHash(
                nodes[0].blockQueries(DEFAULT_CHAIN_IID).getLastBlockHeight().get()
        )
    }

    private fun buildBlockWithOps(
            nodes: List<PostchainTestNode> = getChainNodes(DEFAULT_CHAIN_IID),
            builder: (GtxBuilder) -> Unit
    ) {
        val brid = nodes[0].getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine.blockchainRid
        val transactionFactory = nodes[0].getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine.getConfiguration().getTransactionFactory()

        buildBlock(nodes.subList(0, 3), DEFAULT_CHAIN_IID,
                transactionFactory.decodeTransaction(GtxBuilder(brid, emptyList(),
                        cryptoSystem, GtvMerkleHashCalculatorV2(cryptoSystem)).apply(builder).finish().buildGtx().encode()))
    }

    fun Assert<PostchainTestNode>.hasIdenticalReusableTableContentAs(expectedNode: PostchainTestNode) {
        given { actualNode ->
            assertk.assertThat(actualNode).hasIdenticalTableContentAs(expectedNode) { ctx ->
                val ids = mutableListOf<String>()
                ctx.conn.createStatement().use { stmt ->
                    val tableName = VectorDbDatabaseAccess().getReusableDatumIdTableName(ctx)
                    stmt.executeQuery("SELECT $REUSABLE_DATUM_ID_COLUMN_DATUM_ID FROM $tableName ORDER BY $REUSABLE_DATUM_ID_COLUMN_DATUM_ID").use { rs ->
                        while (rs.next()) {
                            ids.add(rs.getString(1))
                        }
                    }
                    mapOf("reusable_datum_ids" to ids)
                }
            }
        }
    }
}

