package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.base.snapshot.SnapshotBlockchainConfigurationData
import net.postchain.base.snapshot.SnapshotPageStore
import net.postchain.base.withReadConnection
import net.postchain.common.data.Hash
import net.postchain.concurrent.util.get
import net.postchain.devtools.ManagedModeTest
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.utils.configuration.NodeSetup
import net.postchain.ebft.syncmanager.common.SnapshotSynchronizer
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtx.GTXBlockchainConfigurationFactory
import net.postchain.gtx.GtxBuilder
import net.postchain.gtx.SNAPSHOT_TABLE_PREFIX
import net.postchain.gtx.extensions.vectordb.helpers.getVectors
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetStrings
import net.postchain.test.modify
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.test.appender.ListAppender
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

// We must only create one per name per run since multiple with mess things up
private var SINGLETON_LOG_APPENDERS = mutableMapOf<String, ListAppender>()

fun createLogCaptor(cls: Class<*>, name: String): ListAppender {
    return SINGLETON_LOG_APPENDERS[name] ?: run{
        val context = LoggerContext.getContext(false)
        val logger = context.getLogger(cls) as Logger
        val appender = ListAppender(name).apply {
            start()
        }
        context.configuration.addLoggerAppender(logger, appender)
        SINGLETON_LOG_APPENDERS[name] = appender
        appender
    }
}


class VectorDbSnapshotIT : ManagedModeTest() {

    private val nodeConfigurationOverrides = mutableMapOf<String, Any>()
    private val appender = createLogCaptor(SnapshotSynchronizer::class.java, "List")

    override fun addNodeConfigurationOverrides(nodeSetup: NodeSetup) {
        super.addNodeConfigurationOverrides(nodeSetup)
        nodeSetup.nodeSpecificConfigs.setProperty("snapshotsync.threshold", 0) // Always sync by default
        nodeConfigurationOverrides.forEach { (key, value) -> nodeSetup.nodeSpecificConfigs.setProperty(key, value) }
    }

    @BeforeEach
    fun beforeEach() {
        appender.clear()
    }

    /** With 4 nodes, create vectors and then do a clean restart of node 4 to make it sync snapshot. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun syncFromSnapshot() {
        val messages = 30

        startManagedSystem(4, 0, restApi = true)

        val config = GtvMLParser.parseGtvML(Any::class::class.java.getResource("/chains/vector_example_test.xml")!!.readText())
                .modify(listOf("snapshot")) {
                    gtv("interval" to gtv(1))
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
        val node0RootHash = getSnapshotRootHash(nodes[0], DEFAULT_CHAIN_IID, height, config)

        // Assert that we could snapshot sync the chain on the replica node
        restartNodeClean(3, DEFAULT_CHAIN_IID, -1)
        Awaitility.await().atMost(Duration.TEN_MINUTES).untilAsserted {

            // Make sure we actually ran snapshot sync
            assertThat(appender.eventsForNodeContains(nodes[3], "Snapshot sync starts from nodes")).isTrue()
            assertThat(appender.eventsForNodeContains(nodes[3], "Finished snapshot syncing successfully")).isTrue()

            assertThat(getSnapshotRootHash(nodes[3], DEFAULT_CHAIN_IID, height, config)).isEqualTo(node0RootHash)
        }
    }

    private fun getSnapshotRootHash(node: PostchainTestNode, chainId: Long, node0Height: Long, config: Gtv): Hash {
        val replicaRootHash = withReadConnection(node.postchainContext.blockBuilderStorage, chainId) { ctx ->
            SnapshotPageStore(ctx, getLevelsPerPage(config), 0, SimpleDigestSystem(node.appConfig.cryptoSystem),
                    "${SNAPSHOT_TABLE_PREFIX}_root")
                    .getRootHashAtHeight(node0Height)
        }
        return replicaRootHash
    }

    private fun getLevelsPerPage(config: Gtv) =
            config["snapshot"]?.get("levels_per_page")?.asInteger()?.toInt()
                    ?: SnapshotBlockchainConfigurationData.default.levelsPerPage

    private fun ListAppender.eventsForNode(node: PostchainTestNode) =
            events.filter { it.contextData.getValue<String>("node.pubkey") == node.appConfig.pubKey  }

    private fun ListAppender.eventsForNodeContains(node: PostchainTestNode, message: String) =
            eventsForNode(node).any { it.message.toString().contains(message) }

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

