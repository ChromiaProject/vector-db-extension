package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.chain0.common.init.initOperation
import net.postchain.chain0.common.operations.registerNodeWithUnitsOperation
import net.postchain.chain0.common.queries.getBlockchainInfo
import net.postchain.chain0.direct_container.createContainerOperation
import net.postchain.chain0.model.ProviderInfo
import net.postchain.chain0.model.ProviderTier
import net.postchain.chain0.proposal_provider.proposeProvidersOperation
import net.postchain.client.core.PostchainClient
import net.postchain.client.transaction.awaitConfirmation
import net.postchain.dapp.postTransactionUntilConfirmed
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetIdAndDistance
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetStrings
import net.postchain.gtx.extensions.vectordb.vector_example.MessageData
import net.postchain.gtx.extensions.vectordb.vector_example.addMessagesOperation
import net.postchain.gtx.extensions.vectordb.vector_example.deleteMessageOperation
import net.postchain.images.common.ManagedModeBase
import net.postchain.images.directory1.Directory1TestBase.Companion.provider1KeyPair
import net.postchain.images.directory1.Directory1TestBase.Companion.provider2KeyPair
import net.postchain.images.directory1.Directory1TestBase.Companion.provider3KeyPair
import net.postchain.images.directory1.awaitUntilAsserted
import net.postchain.test.modify
import org.awaitility.Duration
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

    override var chain0Config = GtvMLEncoder.encodeXMLGtv(
            GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/manager.xml")!!.readText()
            ).modify(listOf("gtx", "rell", "moduleArgs", "proposal_blockchain.util", "allowed_dapp_chain_gtx_modules")) { configEntry ->
                gtv(listOf(
                        *configEntry.asArray(),
                        gtv("net.postchain.gtx.extensions.vectordb.VectorDbGTXModule"),
                ))
            })

    private val dappName = "vector_example"
    private lateinit var vectorClient: PostchainClient
    val dappConfig = this::class.java.getResource("/chains/vector_example.xml")!!.readText()

    @Test
    @Order(10)
    fun `setup the network`() {

        testLogger.info("Setup the network")

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
            transactionBuilder()
                    .initOperation(null, null)
                    .postTransactionUntilConfirmed("init")

            assertChainSigners(chain0Brid, node1)
        }

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

        deployDapp(dappName, "container1", dappConfig,
                assertSigners = nodes())

        node1.c0.getBlockchainInfo(dapps[dappName]!!.data)!!.apply {
            testLogger.info("Blockchain info: $this")
        }

        vectorClient = node1.client(dapps[dappName]!!)
    }

    @Test
    @Order(30)
    fun `add messages`() {
        vectorClient.transactionBuilder().addMessagesOperation(listOf(
                MessageData("hello", "[0.1, 0.2, 0.3]"),
                MessageData("world", "[0.4, 0.5, 0.6]")
        ))
                .postTransactionUntilConfirmed("Add vectors")
                .awaitConfirmation(vectorClient, retries = 100, pollInterval = java.time.Duration.ofSeconds(2))
    }

    @Test
    @Order(40)
    fun `query - without query template`() {
        awaitUntilAsserted(atMost = Duration.ONE_MINUTE) {
            assertThat(
                    vectorClient.queryClosestObjectsGetIdAndDistance("messages", 0,
                            "[0.1, 0.2, 0.3]", 1.0, 1)
            ).isEqualTo(listOf(
                    mapOf(
                            "id" to 1L,
                            "distance" to "0"
                    )
            ))
        }
    }

    @Test
    @Order(41)
    fun `query - with template`() {
        assertThat(
                vectorClient.queryClosestObjectsGetStrings("messages", 0,
                        "[0.3, 0.3, 0.3]", 1.0, 2, "get_messages")
        ).isEqualTo(listOf("world", "hello"))
    }

    @Test
    @Order(50)
    fun `delete message`() {

        // Delete message
        vectorClient.transactionBuilder().deleteMessageOperation("hello")
                .postTransactionUntilConfirmed("Delete vectors")
                .awaitConfirmation(vectorClient)

        // Hello is no longer returned
        awaitUntilAsserted {
            assertThat(
                    vectorClient.queryClosestObjectsGetStrings("messages", 0,
                            "[0.3, 0.3, 0.3]", 1.0, 2, "get_messages")
            ).isEqualTo(listOf("world"))
        }
    }

    @AfterAll
    fun cleanup() {
        super.breakdown()
    }
}
