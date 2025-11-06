package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.extracting
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBody
import net.postchain.gtx.GtxOp
import net.postchain.gtx.extensions.vectordb.config.VectorDBIndex
import net.postchain.gtx.extensions.vectordb.helpers.VectorDbTestExceptionCaptorEventProcessor
import net.postchain.gtx.extensions.vectordb.helpers.addCollection
import net.postchain.gtx.extensions.vectordb.helpers.addMessage
import net.postchain.gtx.extensions.vectordb.helpers.addMessages
import net.postchain.gtx.extensions.vectordb.helpers.addMessagesInCollection
import net.postchain.gtx.extensions.vectordb.helpers.buildQueryTemplateOrNull
import net.postchain.gtx.extensions.vectordb.helpers.changeCollection
import net.postchain.gtx.extensions.vectordb.helpers.deleteMessage
import net.postchain.gtx.extensions.vectordb.helpers.deleteMessages
import net.postchain.gtx.extensions.vectordb.helpers.deleteMessagesInCollection
import net.postchain.gtx.extensions.vectordb.helpers.getVectorCollections
import net.postchain.gtx.extensions.vectordb.helpers.getVectors
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjects
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetIdAndDistance
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetStrings
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsGetTextAndDistance
import net.postchain.gtx.extensions.vectordb.helpers.queryClosestObjectsNoTemplate
import net.postchain.gtx.extensions.vectordb.helpers.removeCollection
import net.postchain.images.directory1.awaitUntilAsserted
import net.postchain.test.modify
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.Test

class VectorDbIT : IntegrationTestSetup() {

    init {
        configOverrides.setProperty("messaging.port", 0)
    }

    @Test
    fun `basics - add, query and delete`() {

        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "hello", "[1, 2, 3]")
        buildBlock(DEFAULT_CHAIN_IID)

        val queryResults = queryClosestObjectsGetStrings(engine, "messages", 0, "[1, 2, 3]", 1.0, 1, "get_messages")

        assertThat(queryResults).hasSize(1)
        assertThat(queryResults[0]).isEqualTo("hello")

        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(1)

        addMessages(engine, listOf(
                "abc" to "[1, 2, 3]",
                "def" to "[1, 2, 3]",
                "ghi" to "[1, 2, 3]",
        ))
        buildBlock(DEFAULT_CHAIN_IID)
        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(4)

        deleteMessages(engine, listOf("abc", "def", "ghi"))
        deleteMessage(engine, "hello")
        buildBlock(DEFAULT_CHAIN_IID)
        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(0)
    }

    @Test
    fun `query - different limitations`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "alpha", "[1, 2, 3]")
        addMessage(engine, "beta", "[1, 4, 3]")
        addMessage(engine, "charlie", "[7, 4, 3]")
        addMessage(engine, "dave", "[9, 8, 4]")
        addMessage(engine, "eve", "[2, 3, 7]")
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(
                queryClosestObjectsGetStrings(engine, "messages", 0, "[1, 2, 3]", 1.0, 3, "get_messages")
        ).isEqualTo(listOf("alpha", "eve", "beta"))

        assertThat(
                queryClosestObjectsGetStrings(engine, "messages", 0, "[1, 2, 3]", 0.02, 3, "get_messages")
        ).isEqualTo(listOf("alpha", "eve"))
    }

    @Test
    fun `query - with distance`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "alpha", "[1, 2, 3]")
        addMessage(engine, "beta", "[1, 4, 3]")
        addMessage(engine, "charlie", "[7, 4, 3]")
        addMessage(engine, "dave", "[9, 8, 4]")
        addMessage(engine, "eve", "[2, 3, 7]")
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(
                queryClosestObjectsGetTextAndDistance(engine, "messages", 0, "[1, 2, 3]", 1.0, 3, "get_messages_with_distance")
        ).isEqualTo(listOf(
                mapOf("text" to "alpha", "distance" to "0"),
                mapOf("text" to "eve", "distance" to "0.015675861711910488"),
                mapOf("text" to "beta", "distance" to "0.056543646950273474")
        ))

        assertThat(
                queryClosestObjectsGetTextAndDistance(engine, "messages", 0, "[1, 2, 3]", 0.02, 3, "get_messages_with_distance")
        ).isEqualTo(listOf(
                mapOf("text" to "alpha", "distance" to "0"),
                mapOf("text" to "eve", "distance" to "0.015675861711910488"),
        ))
    }

    @Test
    fun `query - with custom template arguments`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "alpha", "[1, 2, 3]")
        addMessage(engine, "beta", "[1, 4, 3]")
        addMessage(engine, "charlie", "[7, 4, 3]")
        addMessage(engine, "dave", "[9, 8, 4]")
        addMessage(engine, "eve", "[2, 3, 7]")
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(
                queryClosestObjects(engine, VECTOR_DB_QUERY_CLOSEST_OBJECTS, "messages", 0, "[1, 2, 3]", 1.0, 3,
                        buildQueryTemplateOrNull("get_messages_with_filter",
                                gtv(mapOf(
                                        "text_filter" to gtv("v"),
                                ))
                        )
                ).asArray().map { it.asString() }
        ).isEqualTo(listOf("eve"))
    }

    @Test
    fun `query - without query template`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "alpha", "[1, 2, 3]")
        addMessage(engine, "beta", "[1, 4, 3]")
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(
                queryClosestObjectsGetIdAndDistance(engine, "messages", 0, "[1, 2, 3]", 0.0, 1)
        ).isEqualTo(listOf(
                mapOf(
                        "id" to 1L,
                        "distance" to "0"
                )
        ))
    }

    @Test
    fun `query - without query template - l2`() {
        val node = createNodes(1, "/chains/vector_example_test_hnsw_l2.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "alpha", "[1, 2, 3]")
        addMessage(engine, "beta", "[1, 4, 3]")
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(
                queryClosestObjectsGetIdAndDistance(engine, "messages", 0, "[1, 2, 3]", 0.0, 1)
        ).isEqualTo(listOf(
                mapOf(
                        "id" to 1L,
                        "distance" to "0"
                )
        ))
    }

    @Test
    fun `query - with and without context`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "hello", "[1, 2, 3]")
        buildBlock(DEFAULT_CHAIN_IID)

        // No context = search in all contexts
        var queryResults = queryClosestObjectsGetStrings(engine, "messages", null, "[1, 2, 3]", 1.0, 1, "get_messages")
        assertThat(queryResults).hasSize(1)
        assertThat(queryResults[0]).isEqualTo("hello")

        // Context 0
        queryResults = queryClosestObjectsGetStrings(engine, "messages", 0, "[1, 2, 3]", 1.0, 1, "get_messages")
        assertThat(queryResults).hasSize(1)
        assertThat(queryResults[0]).isEqualTo("hello")

        // Context 100 (no message in that context)
        queryResults = queryClosestObjectsGetStrings(engine, "messages", 100, "[1, 2, 3]", 1.0, 1, "get_messages")
        assertThat(queryResults).hasSize(0)
    }

    @Test
    fun `query - without context and multiple hits`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        buildBlock(DEFAULT_CHAIN_IID)

        // Context 1
        val op1 = GtxOp("add_messages_in_context", gtv(1), gtv(
                listOf(gtv(gtv("hello 1"), gtv("[1, 2, 3]")))))

        // Context 2
        val op2 = GtxOp("add_messages_in_context", gtv(2), gtv(
                listOf(gtv(gtv("hello 2"), gtv("[1, 2, 3]")))))
        val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(
                Gtx(GtxBody(engine.getConfiguration().blockchainRid, listOf(op1, op2), listOf()), listOf()).encode()
        )
        buildBlock(DEFAULT_CHAIN_IID, tx)

        // No context = search in all contexts
        var queryResults = queryClosestObjectsNoTemplate(engine, "messages", null, "[1, 2, 3]", 1.0, 10)
        assertThat(queryResults).hasSize(2)
        assertThat(queryResults.any { it.first == 1L && it.second == 1L }).isTrue()
        assertThat(queryResults.any { it.first == 2L && it.second == 2L }).isTrue()

        // Context 1
        queryResults = queryClosestObjectsNoTemplate(engine, "messages", 1, "[1, 2, 3]", 1.0, 10)
        assertThat(queryResults).hasSize(1)
        assertThat(queryResults.any { it.first == 1L && it.second == 1L }).isTrue()
    }

    @Test
    fun `test add and delete`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        addMessage(engine, "alpha", "[1, 2, 3]")
        addMessage(engine, "beta", "[1, 2, 3]")
        addMessage(engine, "charlie", "[1, 2, 3]")
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(3)

        deleteMessage(engine, "beta")
        buildBlock(DEFAULT_CHAIN_IID)
        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(2)

        addMessage(engine, "delta", "[1, 2, 3]")
        deleteMessage(engine, "charlie")
        buildBlock(DEFAULT_CHAIN_IID)
        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(2)

        addMessage(engine, "dave", "[1, 2, 3]")
        deleteMessage(engine, "dave")
        deleteMessage(engine, "alpha")
        buildBlock(DEFAULT_CHAIN_IID)
        assertThat(getVectors(engine, DEFAULT_CHAIN_IID, "messages")).hasSize(1)
    }

    @Test
    fun `reject config with new distance type`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]

        val blockchainGtvConfig = readBlockchainConfig("/chains/vector_example_test.xml")
                .modify(listOf("gtx", "modules")) {
                    gtv(gtv("net.postchain.gtx.extensions.vectordb.helpers.VectorDbTestExceptionCaptorGTXModule"))
                }
                .modify(listOf("vector_db_extension", "collections", "messages", "index")) {
                    gtv(VectorDBIndex.HNSW_L2.name)
                }
        node.addConfiguration(DEFAULT_CHAIN_IID, 2, blockchainGtvConfig)

        Awaitility.await().atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    buildBlockNoWait(listOf(node), DEFAULT_CHAIN_IID, 2)
                    assertThat(VectorDbTestExceptionCaptorEventProcessor.INIT_EXCEPTION).isNotNull().hasMessage("Changing embedded index is not supported for collection messages")
                }
    }

    @Test
    fun `dynamic collection - create and delete`() {
        val node = createNodes(1, "/chains/vector_example_test_dynamic_collections.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine
        val collectionName = "customers"

        addCollection(engine, collectionName, 128, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        awaitUntilAsserted {
            val collections = getVectorCollections(engine)
            assertThat(collections).extracting { it.name }.containsOnly(collectionName)
        }

        removeCollection(engine, collectionName)
        buildBlock(DEFAULT_CHAIN_IID)
        val collectionsAfterDelete = getVectorCollections(engine)
        assertThat(collectionsAfterDelete).isEmpty()
    }

    @Test
    fun `dynamic collection - duplicate not allowed`() {
        val node = createNodes(1, "/chains/vector_example_test_dynamic_collections.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine
        val collectionName = "customers"

        addCollection(engine, collectionName, 1, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        val txRid = addCollection(engine, collectionName, 2, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        val reason = engine.getTransactionQueue().getRejectionReason(txRid)
        assertThat(reason?.first).isNotNull()
                .hasMessage("Collection customers already exists")
    }

    @Test
    fun `static collection - remove not allowed`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine
        val collectionName = "messages"
        buildBlock(DEFAULT_CHAIN_IID)

        val collections = getVectorCollections(engine)
        assertThat(collections).extracting { it.name }.containsOnly(collectionName)

        removeCollection(engine, collectionName)
        buildBlock(DEFAULT_CHAIN_IID)

        val collectionsAfterDelete = getVectorCollections(engine)
        assertThat(collectionsAfterDelete).extracting { it.name }.containsOnly(collectionName)
    }

    @Test
    fun `collection -  add and update`() {
        val node = createNodes(1, "/chains/vector_example_test_dynamic_collections.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine
        val collectionName = "customers"

        addCollection(engine, collectionName, 128, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        val collections = getVectorCollections(engine)
        assertThat(collections).extracting { Triple(it.name, it.maxVectors, it.storeBatchSize) }
                .containsOnly(Triple(collectionName, 10L, 100L))

        changeCollection(engine, collectionName, queryMaxVectors = 50, storeBatchSize = 200)
        buildBlock(DEFAULT_CHAIN_IID)
        val collectionsAfterUpdate = getVectorCollections(engine)
        assertThat(collectionsAfterUpdate).extracting { Triple(it.name, it.maxVectors, it.storeBatchSize) }
                .containsOnly(Triple(collectionName, 50L, 200L))

        changeCollection(engine, collectionName, queryMaxVectors = null, storeBatchSize = 123)
        buildBlock(DEFAULT_CHAIN_IID)
        val collectionsAfterPartialUpdate = getVectorCollections(engine)
        assertThat(collectionsAfterPartialUpdate).extracting { Triple(it.name, it.maxVectors, it.storeBatchSize) }
                .containsOnly(Triple(collectionName, 50L, 123L))
    }

    @Test
    fun `collections - skip static collections not part of config`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine
        val newCollection = "new_messages"

        addMessage(engine, "alpha", "[1, 2, 3]")
        buildBlock(DEFAULT_CHAIN_IID)

        val blockchainGtvConfig = readBlockchainConfig("/chains/vector_example_test.xml")
                .modify(listOf("vector_db_extension", "collections")) {
                    gtv(mapOf(
                            "new_messages" to gtv(
                                    mapOf(
                                            "dimensions" to gtv(128),
                                            "query_max_vectors" to gtv(20),
                                            "index" to gtv("hnsw_cosine"),
                                    )
                            )
                    ))
                }
        node.addConfiguration(DEFAULT_CHAIN_IID, 2, blockchainGtvConfig)
//        buildBlock(DEFAULT_CHAIN_IID)

        Awaitility.await().atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    buildBlockNoWait(listOf(node), DEFAULT_CHAIN_IID, 2)
                    val newEngine = node.getBlockchainInstance().blockchainEngine
                    val collections = getVectorCollections(newEngine)
                    assertThat(collections).extracting { it.name }.containsOnly(newCollection)
                }
    }

    @Test
    fun `collections - origin modes - static can't add dynamic`() {
        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        buildBlock(DEFAULT_CHAIN_IID)

        val newEngine = node.getBlockchainInstance().blockchainEngine
        val txRid = addCollection(newEngine, "dynamic", 128, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(engine.getTransactionQueue().getRejectionReason(txRid)?.first)
                .isNotNull()
                .hasMessage("Dynamic collection support is disabled in the configuration")
    }

    @Test
    fun `collections - origin modes - static can't started when dynamics exists`() {

        val node = createNodes(1, "/chains/vector_example_test_dynamic_collections.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        buildBlock(DEFAULT_CHAIN_IID)
        assertThat(getVectorCollections(engine)).hasSize(0)

        addCollection(engine, "dynamic", 128, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        val blockchainGtvConfig = readBlockchainConfig("/chains/vector_example_test.xml")
                .modify(listOf("gtx", "modules")) {
                    gtv(gtv("net.postchain.gtx.extensions.vectordb.helpers.VectorDbTestExceptionCaptorGTXModule"))
                }
        node.addConfiguration(DEFAULT_CHAIN_IID, 3, blockchainGtvConfig)

        Awaitility.await().atMost(Duration.TEN_SECONDS)
                .untilAsserted {
                    buildBlockNoWait(listOf(node), DEFAULT_CHAIN_IID, 3)
                    assertThat(VectorDbTestExceptionCaptorEventProcessor.INIT_EXCEPTION)
                            .isNotNull()
                            .hasMessage("Database initialized with static collections, but dynamic collections exist in DB")
                }
    }

    @Test
    fun `dynamic collection - restart node `() {
        val node = createNodes(1, "/chains/vector_example_test_dynamic_collections.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine
        val collectionName = "customers"

        addCollection(engine, collectionName, 128, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        val collections = getVectorCollections(engine)
        assertThat(collections).extracting { it.name }.containsOnly(collectionName)

        node.stopBlockchain(DEFAULT_CHAIN_IID)
        node.startBlockchain(DEFAULT_CHAIN_IID)

        val newEngine = node.getBlockchainInstance().blockchainEngine
        val collectionsAfterRestart = getVectorCollections(newEngine)
        assertThat(collectionsAfterRestart).extracting { it.name }.containsOnly(collectionName)
    }

    @Test
    fun `add vector with incorrect dimensions`() {

        val node = createNodes(1, "/chains/vector_example_test.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine

        val txRid = addMessage(engine, "hello", "[1, 2]")
        buildBlock(DEFAULT_CHAIN_IID)

        val reason = engine.getTransactionQueue().getRejectionReason(txRid)
        assertThat(reason?.first).isNotNull()
                .hasMessage("Vector 1 has 2 dimensions, but the collection requires 3 dimensions")
    }

    @Test
    fun `add and delete messages in dynamic collections`() {
        val node = createNodes(1, "/chains/vector_example_test_dynamic_collections.xml")[0]
        val engine = node.getBlockchainInstance().blockchainEngine
        var seq = 0L

        addCollection(engine, "collection_1", 3, VectorDBIndex.HNSW_COSINE, 10, 100)
        addCollection(engine, "collection_2", 3, VectorDBIndex.HNSW_COSINE, 10, 100)
        addCollection(engine, "collection_3", 3, VectorDBIndex.HNSW_COSINE, 10, 100)
        buildBlock(DEFAULT_CHAIN_IID)

        addMessage(engine, "collection_1", "hello_${seq++}", "[1, 2, 3]")
        addMessage(engine, "collection_2", "hello_${seq++}", "[1, 2, 3]")
        addMessage(engine, "collection_3", "hello_${seq++}", "[1, 2, 3]")
        buildBlock(DEFAULT_CHAIN_IID)

        addMessagesInCollection(engine, "collection_1", listOf("hello_${seq++}" to "[1, 2, 3]", "hello_${seq++}" to "[1, 2, 3]", "hello_${seq++}" to "[1, 2, 3]"))
        addMessagesInCollection(engine, "collection_2", listOf("hello_${seq++}" to "[1, 2, 3]", "hello_${seq++}" to "[1, 2, 3]", "hello_${seq++}" to "[1, 2, 3]"))
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(queryClosestObjectsNoTemplate(engine, "collection_1", 0, "[1, 2, 3]", 10.0, 10).map { it.second })
                .isEqualTo(listOf(1L, 4L, 5L, 6L))
        assertThat(queryClosestObjectsNoTemplate(engine, "collection_2", 0, "[1, 2, 3]", 10.0, 10).map { it.second })
                .isEqualTo(listOf(2L, 7L, 8L, 9L))

        deleteMessagesInCollection(engine, "collection_1", listOf("hello_0", "hello_4"))
        buildBlock(DEFAULT_CHAIN_IID)

        assertThat(queryClosestObjectsNoTemplate(engine, "collection_1", 0, "[1, 2, 3]", 10.0, 10).map { it.second })
                .isEqualTo(listOf(4L, 6L))
    }
}
