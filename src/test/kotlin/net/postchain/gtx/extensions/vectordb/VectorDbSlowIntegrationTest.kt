package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import net.postchain.chain0.common.init.initOperation
import net.postchain.chain0.common.operations.registerNodeWithUnitsOperation
import net.postchain.chain0.common.queries.getBlockchainInfo
import net.postchain.chain0.direct_container.createContainerOperation
import net.postchain.chain0.model.ProviderInfo
import net.postchain.chain0.model.ProviderTier
import net.postchain.chain0.proposal.BlockchainConfigurationUpdateState
import net.postchain.chain0.proposal.ProposalType
import net.postchain.chain0.proposal.getBlockchainConfigurationUpdateAttemptStateByProposal
import net.postchain.chain0.proposal.getRelevantProposals
import net.postchain.chain0.proposal_blockchain.proposeConfigurationOperation
import net.postchain.chain0.proposal_provider.proposeProvidersOperation
import net.postchain.client.core.PostchainClient
import net.postchain.client.transaction.awaitConfirmation
import net.postchain.dapp.postTransactionUntilConfirmed
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtx.extensions.vectordb.config.VectorDBIndex
import net.postchain.gtx.extensions.vectordb.helpers.modifyGTV
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetIdAndDistance
import net.postchain.gtx.extensions.vectordb.vector_example.MessageData
import net.postchain.gtx.extensions.vectordb.vector_example.addMessagesOperation
import net.postchain.images.common.ManagedModeBase
import net.postchain.images.directory1.Directory1TestBase.Companion.provider1KeyPair
import net.postchain.images.directory1.Directory1TestBase.Companion.provider2KeyPair
import net.postchain.images.directory1.Directory1TestBase.Companion.provider3KeyPair
import net.postchain.images.directory1.awaitUntilAsserted
import org.junit.jupiter.api.AfterAll
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
class VectorDbSlowIntegrationTest : ManagedModeBase("vectordb") {

    private val dappName = "vector_example"
    private lateinit var vectorClient: PostchainClient

    @Test
    @Order(10)
    fun `setup the network`() {

        // Fail fast on missing dapp config
        assertThat(this::class.java.getResource("/directory1deployment/$dappName.xml")).isNotNull()

        testLogger.info("Setup the network")

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

        node2 = postchainServerWithSubnodes("node2",
                provider2KeyPair,
                "/net/postchain/gtx/extensions/vectordb/config-all-subnodes",
                true)
                .withGenesisNode(node1)

        node3 = postchainServerWithSubnodes("node3",
                provider3KeyPair,
                "/net/postchain/gtx/extensions/vectordb/config-all-subnodes",
                true)
                .withGenesisNode(node1)

        removeSubnodeContainers()
        startNodesAndChain0()
        getDb(node1).awaitBlockHeight(0)
        with(node1.c0) {
            val clusterAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/cluster_anchoring.xml")!!.readText())
            val systemAnchoringGtvConfig = GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/system_anchoring.xml")!!.readText())

            transactionBuilder()
                    .initOperation(GtvEncoder.encodeGtv(systemAnchoringGtvConfig), GtvEncoder.encodeGtv(clusterAnchoringGtvConfig))
                    .postTransactionUntilConfirmed("init")

            assertChainSigners(chain0Brid, node1)
        }
        assertAnchoringChainProperties()

        testLogger.info("Adding system providers provider2-4 and node2-3")
        val newProviders = listOf(
                ProviderInfo(node2.provider.pubKey.wData, "provider2", "https://provider2.com"),
                ProviderInfo(node3.provider.pubKey.wData, "provider3", "https://provider3.com"),
        )

        node1.client(chain0Brid, listOf(node1.provider, node2.provider, node3.provider)).transactionBuilder().addNop()
                .createContainerOperation(node1.providerPubkey, "container1", "system", 1, listOf(node1.providerPubkey))
                .proposeProvidersOperation(node1.providerPubkey, newProviders, ProviderTier.NODE_PROVIDER, system = true, active = true, description = "")
                .registerNodeWithUnitsOperation(node2.providerPubkey, node2.pubkey.data, node2.nodeHost, node2.nodePort.toLong(), node2.nodeApiPath(), listOf(systemCluster), 2)
                .registerNodeWithUnitsOperation(node3.providerPubkey, node3.pubkey.data, node3.nodeHost, node3.nodePort.toLong(), node3.nodeApiPath(), listOf(systemCluster), 2)
                .postTransactionUntilConfirmed("Create container")
                .awaitConfirmation(node1.c0)
    }

    @Test
    @Order(20)
    fun `deploy vector dapp`() {

        deployDapp(dappName, "container1", assertSigners = nodes())

        node1.c0.getBlockchainInfo(dapps[dappName]!!.data)!!.apply {
            testLogger.info("Blockchain info: $this")
        }

        vectorClient = node1.client(dapps[dappName]!!)
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
                    "collection" to gtv("messages"),
                    "context" to gtv(0),
                    "q_vector" to gtv("[1.0, 2.5, 3.0]"),
                    "max_distance" to gtv("1.0"),
                    "query_max_vectors" to gtv(2),
                    "query_template" to gtv(
                            "type" to gtv("get_messages"),
                    ),
            ))
            assertThat(result.asArray().map { it.asString() }).isEqualTo(listOf("hello", "world"))
        }
    }

    @Test
    @Order(40)
    fun `query - without query template`() {

        assertThat(
                vectorClient.queryClosestObjectsGetIdAndDistance("messages", 0, "[1, 2, 3]", 0.0, 1)
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
        val config = modifyGTV(originalConfig, listOf("vector_db_extension", "collections", "messages")) { configEntry ->
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
}
