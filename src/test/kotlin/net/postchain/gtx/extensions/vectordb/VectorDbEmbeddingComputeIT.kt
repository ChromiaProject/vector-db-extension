package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.query
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GtxOp
import net.postchain.gtx.extensions.vectordb.helpers.MockEmbeddingRestApi
import net.postchain.gtx.extensions.vectordb.helpers.buildTransaction
import net.postchain.gtx.extensions.vectordb.helpers.generateVector
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsNoTemplate
import net.postchain.images.directory1.awaitUntilAsserted
import net.postchain.images.directory1.testLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

@Timeout(10, unit = TimeUnit.MINUTES)
class VectorDbEmbeddingComputeIT : PGVectorBaseTest() {

    private val embeddingService = MockEmbeddingRestApi()

    @BeforeEach
    fun beforeEach() {
        embeddingService.start()
    }

    @AfterEach
    fun afterEach() {
        embeddingService.close()
    }

    @Test
    fun `compute multiple embeddings`() {
        val text1 = "Blockchains are primitive and experimental compared with databases. Traditional blockchains record information in a list format, making it difficult to store and manage complex data sets on-chain. They are also reliant on centralized servers and third-party providers."
        val text2 = "Chromia records structured blockchain data in linked relational tables. This innovation, which we call relational blockchain, makes it possible to query data directly on-chain, perform hundreds of read-and-write operations with a single transaction, and index block data in real-time."
        val text3 = "Chromia CHR token is the native token designed to empower the Chromia platform and foster a mutually beneficial relationship between developers, users, and investors."
        embeddingService.data = mapOf(
                text1 to generateVector(384),
                text2 to generateVector(384),
                text3 to generateVector(384),
        )

        val embeddingServiceUrl = "http://localhost:${embeddingService.port()}"
        testLogger.info("Embedding service URL: $embeddingServiceUrl")
        configOverrides.setProperty("extension.vector_db.embedding.url", embeddingServiceUrl)

        val node = createNodes(3, "/chains/vector_example_embedding_compute_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("embed",
                        gtv("id-1"),
                        gtv(text1)
                ),
                GtxOp("embed",
                        gtv("id-2"),
                        gtv(text2)
                ),
                GtxOp("embed",
                        gtv("id-3"),
                        gtv(text3)
                ),
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            // Verify each embedding is stored in the vector db and can be queried with a distance of 0
            listOf("id-1", "id-2", "id-3").forEach { id ->
                val textEmbedding = node.query(DEFAULT_CHAIN_IID) {
                    it.query("get_text_embedding", gtv(mapOf("id" to gtv(id))))
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

    @Test
    fun `accept similar embeddings`() {
        val text1 = "A block of text"
        embeddingService.data = mapOf(
                text1 to mutableListOf(
                        "[0.7071067812, 0.0, 0.7071067812]", // Compute
                        "[0.7071, 0.0, 0.7071]", // Verify 1
                        "[0.706, 0.01, 0.708]", // Verify 2
                ))

        configOverrides.setProperty("extension.vector_db.embedding.url", "http://localhost:${embeddingService.port()}")

        val node = createNodes(3, "/chains/vector_example_embedding_compute_small_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("embed",
                        gtv("id-1"),
                        gtv(text1)
                ),
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            // Verify each embedding is stored in the vector db and can be queried with a distance of 0
            listOf("id-1").forEach { id ->
                val textEmbedding = node.query(DEFAULT_CHAIN_IID) {
                    it.query("get_text_embedding", gtv(mapOf("id" to gtv(id))))
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
