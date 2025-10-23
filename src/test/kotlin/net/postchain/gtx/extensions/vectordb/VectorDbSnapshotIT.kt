package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import net.postchain.base.withWriteConnection
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
import net.postchain.gtx.extensions.vectordb.helpers.getVectors
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetStrings
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsNoTemplate
import net.postchain.test.modify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class VectorDbSnapshotIT : SnapshotTestBase() {

    /** With 4 nodes, create vectors and then do a clean restart of node 4 to make it sync snapshot. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun syncFromSnapshot() {
        val messages = 30

        startManagedSystem(4, 0, restApi = true)

        // Avoid race condition to create the pg vector extension by making sure the extension is created by one node only
        withWriteConnection(nodes[0].postchainContext.sharedStorage, 0) {
            it.conn.createStatement().use { stmt -> stmt.execute("CREATE EXTENSION IF NOT EXISTS vector") }
            true
        }

        val config = GtvMLParser.parseGtvML(Any::class::class.java.getResource("/chains/vector_example_test.xml")!!.readText())
                .modify(listOf("snapshot")) {
                    gtv("interval" to gtv(1))
                }
                .modify(listOf("features")) { configEntry ->
                    gtv(configEntry.asDict() + mapOf("snapshot_enabled" to gtv(true)))
                }
        startNewBlockchain(setOf(0, 1, 2, 3), setOf(), null, rawBlockchainConfiguration = GtvEncoder.encodeGtv(config), blockchainConfigurationFactory = GTXBlockchainConfigurationFactory())
        buildBlock(DEFAULT_CHAIN_IID)

        addMessagesAndBuildBlock {
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
                        "sys.x.vectordb.collection_ids",
                        "sys.x.vectordb.datum_id_seq",
                )))

        // Verify queries return identical results on all nodes - we can't use query template here since Rell is not restored on node 3
        assertThat(nodes).hasSameState { node ->
            val engine = node.getBlockchainInstance().blockchainEngine
            queryClosestObjectsNoTemplate(engine, "messages", 0, "[0.1, 0.2, 0.3]", 100.0, 1)
        }
    }

    private fun addMessagesAndBuildBlock(
            nodes: List<PostchainTestNode> = getChainNodes(DEFAULT_CHAIN_IID),
            builder: (GtxBuilder) -> Unit
    ) {
        val brid = nodes[0].getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine.blockchainRid
        val transactionFactory = nodes[0].getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine.getConfiguration().getTransactionFactory()

        buildBlock(nodes.subList(0, 3), DEFAULT_CHAIN_IID,
                transactionFactory.decodeTransaction(GtxBuilder(brid, emptyList(),
                        cryptoSystem, GtvMerkleHashCalculatorV2(cryptoSystem)).apply(builder).finish().buildGtx().encode()))
    }
}

