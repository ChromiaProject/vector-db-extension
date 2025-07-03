package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import net.postchain.chain0.common.init.initOperation
import net.postchain.chain0.common.queries.getAllNodes
import net.postchain.chain0.common.queries.getAllProviders
import net.postchain.chain0.common.queries.getBlockchainInfo
import net.postchain.chain0.common.queries.getBlockchains
import net.postchain.chain0.common.queries.getNodeData
import net.postchain.chain0.common.queries.getSummary
import net.postchain.chain0.direct_container.createContainerOperation
import net.postchain.chain0.proposal.BlockchainConfigurationUpdateState
import net.postchain.chain0.proposal.ProposalType
import net.postchain.chain0.proposal.getBlockchainConfigurationUpdateAttemptStateByProposal
import net.postchain.chain0.proposal.getRelevantProposals
import net.postchain.chain0.proposal_blockchain.proposeConfigurationOperation
import net.postchain.client.core.PostchainClient
import net.postchain.client.transaction.awaitConfirmation
import net.postchain.dapp.postTransactionUntilConfirmed
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtx.extensions.vectordb.vector_example.MessageData
import net.postchain.gtx.extensions.vectordb.vector_example.addMessagesOperation
import net.postchain.images.common.ManagedModeBase
import net.postchain.images.directory1.Directory1TestBase.Companion.provider1KeyPair
import net.postchain.images.directory1.awaitUntilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VectorDbIT : ManagedModeBase("vectordb") {

    private lateinit var vectorClient: PostchainClient

    @BeforeAll
    fun init() {
        // Nodes
        val chain0TextConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/manager.xml")!!.readText())
        val chain0ModifiedConfig = modifyGTV(chain0TextConfig, listOf("gtx", "rell", "moduleArgs", "proposal_blockchain.util", "allowed_dapp_chain_gtx_modules")) { configEntry ->
            gtv(listOf(
                    *configEntry.asArray(),
                    gtv("net.postchain.gtx.extensions.vectordb.VectorDbGTXModule"),
            ))
        }

        chain0Config = GtvMLEncoder.encodeXMLGtv(chain0ModifiedConfig)
        node1 = postchainServerWithSubnodes("node1",
                provider1KeyPair,
                "/net/postchain/gtx/extensions/vectordb/config-all-subnodes",
                true)

        startNodesAndChain0()
    }

    @Test
    @Order(10)
    fun `setup the network`() {
        testLogger.info("Setup the network")
        getDb(node1).awaitBlockHeight(0)
        with(node1.c0) {
            val clusterAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/cluster_anchoring.xml")!!.readText())
            val systemAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/system_anchoring.xml")!!.readText())
            transactionBuilder()
                    .initOperation(GtvEncoder.encodeGtv(systemAnchoringGtvConfig), GtvEncoder.encodeGtv(clusterAnchoringGtvConfig))
                    .postTransactionUntilConfirmed("init")
            assertThat(getSummary().providers).isEqualTo(1L)
            assertThat(getNodeData(node1.nodeKeyPair.pubKey).active).isTrue()
        }
        assertAnchoringChainProperties()

        assertChainSigners(chain0Brid, *nodes())

        node1.client(chain0Brid, listOf(node1.provider)).transactionBuilder().addNop()
                .createContainerOperation(node1.providerPubkey, "container1", "system", 1, listOf(node1.providerPubkey))
                .postTransactionUntilConfirmed("Create container")
                .awaitConfirmation(node1.c0)

        assertThat(node1.c0.getAllProviders().size).isEqualTo(1)
        assertThat(node1.c0.getAllNodes(true).size).isEqualTo(1)
    }

    @Test
    @Order(20)
    fun `deploy vector dapp`() {
        nodes().forEach { node ->
            assertThat(node.c0.getBlockchains(true).size).isEqualTo(3)
        }

        deployDapp("vector_example", "container1", assertSigners = arrayOf(node1))

        node1.c0.getBlockchainInfo(dapps["vector_example"]!!.data)!!.apply {
            testLogger.info("Blockchain info: $this")
        }

        // Asserting that blockchain is added
        nodes().forEach { node ->
            assertThat(node.c0.getBlockchains(true).size).isEqualTo(4)
        }

        vectorClient = node1.client(dapps["vector_example"]!!)
    }

    @Test
    @Order(30)
    fun `add vectors`() {

        vectorClient.transactionBuilder().addMessagesOperation(listOf(
                MessageData("hello", "[1, 2, 3]"),
                MessageData("world", "[4, 5, 6]")
        ))
                .postTransactionUntilConfirmed("Add vectors")
                .awaitConfirmation(node1.c0)

        awaitUntilAsserted {
            val result = vectorClient.query("query_closest_objects", gtv(
                    "context" to gtv(0),
                    "q_vector" to gtv("[1.0, 2.5, 3.0]"),
                    "max_distance" to gtv("1.0"),
                    "max_vectors" to gtv(2),
                    "query_template" to gtv(
                            "type" to gtv("get_messages"),
                    ),
            ))
            assertThat(result.asArray().map { it.asString() }).isEqualTo(listOf("hello", "world"))
        }
    }

    @Test
    @Order(30)
    fun `query - without query template`() {

        assertThat(
                vectorClient.queryClosestObjectsGetIdAndDistance(0, "[1, 2, 3]", 0.0, 1)
        ).isEqualTo(listOf(
                mapOf(
                        "id" to 1L,
                        "distance" to "0"
                )
        ))
    }

    @Test
    @Order(50)
    fun `fail to update config with new distance index`() {

        val originalConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/vector_example.xml")!!.readText())
        val config = modifyGTV(originalConfig, listOf("vector_db_extension")) { configEntry ->
            gtv(mapOf(
                    *configEntry.asDict().toList().toTypedArray(),
                    "index" to gtv(VectorDBIndex.HNSW_L2.name),
            ))
        }

        node1.c0.transactionBuilder()
                .proposeConfigurationOperation(node1.providerPubkey, dapps["vector_example"]!!, GtvEncoder.encodeGtv(config), "", null)
                .postTransactionUntilConfirmed("Propose new config")
                .awaitConfirmation(node1.c0)

        testLogger.info { "Waiting for configuration to be rejected" }
        awaitUntilAsserted {
            val configProposal = node1.c0.getRelevantProposals(0, Long.MAX_VALUE, false, node1.providerPubkey).lastOrNull {
                it.proposalType == ProposalType.configuration
            }
            assertThat(configProposal).isNotNull()
            val configUpdateAttempt = node1.c0.getBlockchainConfigurationUpdateAttemptStateByProposal(configProposal!!.rowid)
            testLogger.info { "Config update status: ${configUpdateAttempt?.state}" }
            if (configUpdateAttempt?.state == BlockchainConfigurationUpdateState.SUCCESSFUL) {
                throw RuntimeException("Config update attempt should have failed")
            }
            assertThat(configUpdateAttempt?.state).isEqualTo(BlockchainConfigurationUpdateState.FAILED)
        }
    }

    @AfterAll
    fun cleanup() {
        super.breakdown()
    }

    private fun modifyGTV(config: Gtv, dictPath: List<String>, modifier: (Gtv) -> Gtv): Gtv {
        return if (dictPath.isEmpty()) {
            modifier(config)
        } else {
            gtv(config.asDict().mapValues { dictEntry ->
                if (dictEntry.key == dictPath[0]) {
                    modifyGTV(dictEntry.value, dictPath.drop(1), modifier)
                } else {
                    dictEntry.value
                }
            })
        }
    }
}
