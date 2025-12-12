package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.query
import net.postchain.devtools.utils.configuration.NodeSeqNumber
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GtxOp
import net.postchain.gtx.extensions.vectordb.helpers.MockEmbeddingRestApi
import net.postchain.gtx.extensions.vectordb.helpers.buildTransaction
import net.postchain.gtx.extensions.vectordb.helpers.generateVector
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsNoTemplate
import net.postchain.images.directory1.awaitUntilAsserted
import org.apache.commons.configuration2.MapConfiguration
import org.http4k.core.Credentials
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

@Timeout(10, unit = TimeUnit.MINUTES)
class VectorDbEmbeddingComputeIT : PGVectorBaseTest() {

    private val apiUser = "user"
    private val apiPassword = "password"
    private val apiToken = "api-token"
    private val xApiKey = "x-api-key"
    private val embeddingService1 = MockEmbeddingRestApi(
            "rest-model-name-1",
            Credentials(apiUser, apiPassword),
    )
    private val embeddingService2 = MockEmbeddingRestApi(
            "rest-model-name-2",
            authBearer = apiToken
    )
    private val embeddingService3 = MockEmbeddingRestApi(
            "rest-model-name-3",
            xApiKey = "x-api-key"
    )

    @BeforeEach
    fun beforeEach() {
        embeddingService1.start()
        embeddingService2.start()
        embeddingService3.start()
    }

    @AfterEach
    fun afterEach() {
        embeddingService1.close()
        embeddingService2.close()
        embeddingService3.close()
    }

    @Test
    fun `compute multiple embeddings`() {
        val text1 = "Blockchains are primitive and experimental compared with databases. Traditional blockchains record information in a list format, making it difficult to store and manage complex data sets on-chain. They are also reliant on centralized servers and third-party providers."
        val text2 = "Chromia records structured blockchain data in linked relational tables. This innovation, which we call relational blockchain, makes it possible to query data directly on-chain, perform hundreds of read-and-write operations with a single transaction, and index block data in real-time."
        val text3 = "Chromia CHR token is the native token designed to empower the Chromia platform and foster a mutually beneficial relationship between developers, users, and investors."
        embeddingService1.data = mapOf(
                text1 to generateVector(384),
                text2 to generateVector(384),
                text3 to generateVector(384),
        )
        embeddingService2.data = embeddingService1.data
        embeddingService2.data = embeddingService1.data

        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.url", embeddingService1.url)
        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.model", "rest-model-name-1")
        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.basic_auth_user", apiUser)
        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.basic_auth_password", apiPassword)

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
    fun `use multiple services using different model names`() {
        val text1 = "A block of text"
        embeddingService1.data = mapOf(
                text1 to mutableListOf(
                        "[0.7071067812, 0.0, 0.7071067812]", // Compute
                        "[0.7071, 0.0, 0.7071]", // Verify 1
                        "[0.706, 0.01, 0.708]", // Verify 2
                ))
        embeddingService2.data = embeddingService1.data
        embeddingService3.data = embeddingService1.data

        nodeConfigOverrides[NodeSeqNumber(0)] = MapConfiguration(mapOf(
                "extension.vector_db.embedding.qwen3_embedding_0_6b.url" to embeddingService1.url,
                "extension.vector_db.embedding.qwen3_embedding_0_6b.model" to "rest-model-name-1",
                "extension.vector_db.embedding.qwen3_embedding_0_6b.basic_auth_user" to apiUser,
                "extension.vector_db.embedding.qwen3_embedding_0_6b.basic_auth_password" to apiPassword
        ))
        nodeConfigOverrides[NodeSeqNumber(1)] = MapConfiguration(mapOf(
                "extension.vector_db.embedding.qwen3_embedding_0_6b.url" to embeddingService2.url,
                "extension.vector_db.embedding.qwen3_embedding_0_6b.model" to "rest-model-name-2",
                "extension.vector_db.embedding.qwen3_embedding_0_6b.auth_bearer" to apiToken
        ))
        nodeConfigOverrides[NodeSeqNumber(2)] = MapConfiguration(mapOf(
                "extension.vector_db.embedding.qwen3_embedding_0_6b.url" to embeddingService3.url,
                "extension.vector_db.embedding.qwen3_embedding_0_6b.model" to "rest-model-name-3",
                "extension.vector_db.embedding.qwen3_embedding_0_6b.x_api_key" to xApiKey
        ))

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

    @Test
    fun `accept similar embeddings`() {
        val text1 = "A block of text"
        embeddingService2.data = mapOf(
                text1 to mutableListOf(
                        "[0.7071067812, 0.0, 0.7071067812]", // Compute
                        "[0.7071, 0.0, 0.7071]", // Verify 1
                        "[0.706, 0.01, 0.708]", // Verify 2
                ))

        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.url", embeddingService2.url)
        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.model", "rest-model-name-2")
        configOverrides.setProperty("extension.vector_db.embedding.qwen3_embedding_0_6b.auth_bearer", apiToken)

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
