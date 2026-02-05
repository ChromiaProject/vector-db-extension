package net.postchain.gtx.extensions.vectordb.manual

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
import net.postchain.common.types.RowId
import net.postchain.dapp.postTransactionUntilConfirmed
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtx.extensions.vectordb.helpers.MockOpenAIEmbeddingRestApi
import net.postchain.gtx.extensions.vectordb.helpers.MockStaticEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.integration_test.embedding_compute.GetTextEmbeddingResult
import net.postchain.gtx.extensions.vectordb.integration_test.embedding_compute.embedOperation
import net.postchain.gtx.extensions.vectordb.integration_test.embedding_compute.getTextEmbedding
import net.postchain.images.common.ManagedModeBase
import net.postchain.images.directory1.Directory1TestBase
import net.postchain.images.directory1.awaitUntilAsserted
import net.postchain.test.modify
import org.http4k.core.Credentials
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration

/**
 * This is a manual test because it is tricky to mock the embedding REST API in CI, and real containers are usually
 * too large or have unreliable model downloads.
 */
@Disabled
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualVectorDbEmbeddingComputeSlowIntegrationTest : ManagedModeBase("vectordb-embedding-compute-slow-it") {

    override var chain0Config = GtvMLEncoder.encodeXMLGtv(
            GtvMLParser.parseGtvML(this::class.java.getResource("/directory1deployment/manager.xml")!!.readText()
            ).modify(listOf("gtx", "rell", "moduleArgs", "proposal_blockchain.util", "allowed_dapp_chain_gtx_modules")) { configEntry ->
                GtvFactory.gtv(listOf(
                        *configEntry.asArray(),
                        GtvFactory.gtv("net.postchain.gtx.extensions.vectordb.VectorDbGTXModule"),
                        GtvFactory.gtv("net.postchain.hybridcompute.HybridComputeGTXModule"),
                ))
            }.modify(listOf("gtx", "rell", "moduleArgs", "proposal_blockchain.util", "allowed_dapp_chain_sync_exts")) { configEntry ->
                GtvFactory.gtv(listOf(
                        *configEntry.asArray(),
                        GtvFactory.gtv("net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension"),
                ))
            }
    )

    private val dappName = "vector_example"
    private lateinit var vectorClient: PostchainClient
    private val dappConfig = this::class.java
            .getResource("/chains/vector_example_embedding_compute_slow_it.xml")!!.readText()
    private val apiUser = "user"
    private val apiPassword = "password"
    private val bmeddingModel = "qwen3-embedding-0.6b"
    val authBearer = "bearer"
    private val embeddingService = MockOpenAIEmbeddingRestApi(
            bmeddingModel,
            Credentials(apiUser, apiPassword),
            authBearer,
    )

    @BeforeAll
    fun beforeAll() {
        embeddingService.start()
    }

    @AfterAll
    fun afterAll() {
        embeddingService.close()
        super.breakdown()
    }

    @Test
    @Order(5)
    fun `setup the embedding service`() {
        val text1 = "hello world"
        embeddingService.data = mapOf(
                text1 to MockStaticEmbeddingResponse("[0.1, 0.2, 0.3]"),
        )
    }

    @Test
    @Order(10)
    fun `setup the network`() {

        testLogger.info("Setup the network")

        val embeddingServic1eUrl = "http://${System.getProperty("DOCKER_HOST_MASTER", "172.17.0.1")}:${embeddingService.port()}"
        val envVars = mapOf(
                "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_QWEN3_EMBEDDING_0_6B_URL" to embeddingServic1eUrl,
                "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_QWEN3_EMBEDDING_0_6B_MODEL" to bmeddingModel,
                "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_QWEN3_EMBEDDING_0_6B_BASIC_AUTH_USER" to "user",
                "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_QWEN3_EMBEDDING_0_6B_BASIC_AUTH_PASSWORD" to "password",
                "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_QWEN3_EMBEDDING_0_6B_AUTH_BEARER" to authBearer,
        )
        node1 = postchainServerWithSubnodes("node1",
                Directory1TestBase.provider1KeyPair,
                "/net/postchain/gtx/extensions/vectordb/config-all-subnodes",
                true)
                .withEnv(envVars)

        node2 = postchainServerWithSubnodes("node2",
                Directory1TestBase.provider2KeyPair,
                "/net/postchain/gtx/extensions/vectordb/config-all-subnodes",
                true)
                .withGenesisNode(node1)
                .withEnv(envVars)

        node3 = postchainServerWithSubnodes("node3",
                Directory1TestBase.provider3KeyPair,
                "/net/postchain/gtx/extensions/vectordb/config-all-subnodes",
                true)
                .withGenesisNode(node1)
                .withEnv(envVars)

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
    fun `submit embedding computations`() {
        vectorClient.transactionBuilder().embedOperation("id-01", "hello world")
                .postTransactionUntilConfirmed("Add vectors")
                .awaitConfirmation(vectorClient, retries = 100, pollInterval = Duration.ofSeconds(2))
    }

    @Test
    @Order(40)
    fun `await computation result`() {
        awaitUntilAsserted(atMost = org.awaitility.Duration.ONE_MINUTE) {
            assertThat(
                    vectorClient.getTextEmbedding("id-01")
            ).isEqualTo(
                    GetTextEmbeddingResult(RowId(1), "[0.1,0.2,0.3]")
            )
        }
    }
}