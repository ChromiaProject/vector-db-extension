package net.postchain.gtx.extensions.vectordb.manual

import net.postchain.gtx.extensions.vectordb.PGVectorBaseTest
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.query
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GtxOp
import net.postchain.gtx.extensions.vectordb.helpers.SentenceTransformationMiniLMContainer
import net.postchain.gtx.extensions.vectordb.helpers.buildTransaction
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsNoTemplate
import net.postchain.images.directory1.awaitUntilAsserted
import net.postchain.images.directory1.testLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Manual test against a real model (smaller than we use on dev/testnet).
 */
@Disabled
@Timeout(10, unit = TimeUnit.MINUTES)
class ManualVectorDbEmbeddingComputeTest : PGVectorBaseTest() {

    private val embeddingServiceContainer = SentenceTransformationMiniLMContainer()

    @BeforeEach
    fun beforeEach() {
        embeddingServiceContainer.start()
    }

    @AfterEach
    fun afterEach() {
        embeddingServiceContainer.stop()
    }

    @Test
    fun `compute multiple embeddings`() {
        val embeddingServiceUrl = "http://${embeddingServiceContainer.host}:${embeddingServiceContainer.getMappedPort(80)}"
        testLogger.info("Embedding service URL: $embeddingServiceUrl")
        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.model", "sentence-transformers/all-MiniLM-L6-v2")
        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.url", embeddingServiceUrl)

        val node = createNodes(3, "/chains/vector_example_embedding_compute_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("embed",
                        gtv("id-1"),
                        gtv("Blockchains are primitive and experimental compared with databases. Traditional blockchains record information in a list format, making it difficult to store and manage complex data sets on-chain. They are also reliant on centralized servers and third-party providers.")
                ),
                GtxOp("embed",
                        gtv("id-2"),
                        gtv("Chromia records structured blockchain data in linked relational tables. This innovation, which we call relational blockchain, makes it possible to query data directly on-chain, perform hundreds of read-and-write operations with a single transaction, and index block data in real-time.")
                ),
                GtxOp("embed",
                        gtv("id-3"),
                        gtv("Chromia CHR token is the native token designed to empower the Chromia platform and foster a mutually beneficial relationship between developers, users, and investors.")
                ),
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            // Verify each embedding is stored in the vector db and can be queried with a distance of 0
            listOf("id-1", "id-2", "id-3").forEach { id ->
                val textEmbedding = node.query(DEFAULT_CHAIN_IID) {
                    it.query("get_text_embedding", gtv(mapOf("id" to gtv(id), )))
                }

                assertThat(textEmbedding).isNotEqualTo(GtvNull)
                val vector = textEmbedding!!["vector"]!!.asString()
                assertThat(vector).isNotEqualTo("")

                val queryResult = queryClosestObjectsNoTemplate(engine, "texts", null,
                        vector, 0.0, 10)
                assertThat(queryResult).hasSize(1)
                assertThat(queryResult[0].second).isEqualTo(textEmbedding["rowid"]!!.asInteger())
            }
        }
    }
}
