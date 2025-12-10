package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.query
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GtxOp
import net.postchain.gtx.extensions.vectordb.helpers.buildTransaction
import net.postchain.gtx.extensions.vectordb.helpers.getVectors
import net.postchain.gtx.extensions.vectordb.lib.vector_db_query_compute.QueryResult
import net.postchain.gtx.extensions.vectordb.lib.vector_db_query_compute.QueryResultObject
import net.postchain.images.directory1.awaitUntilAsserted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(120)
class VectorDbQueryComputeIT : PGVectorBaseTest() {

    @Test
    fun `compute multiple queries`() {
        val node = createNodes(3, "/chains/vector_example_query_compute_test.xml")[0]

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("add_message", gtv("message 1"), gtv("[0.11, 0.21, 0.31]")),
                GtxOp("add_message", gtv("message 2"), gtv("[0.12, 0.22, 0.32]")),
                GtxOp("add_message", gtv("message 3"), gtv("[0.13, 0.23, 0.33]")),
        )))
        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("submit_query_request",
                        gtv("id-1"),
                        gtv("[0.1, 0.2, 0.3]"),
                        gtv("1.0"),
                        gtv(10),
                ),
                GtxOp("submit_query_request",
                        gtv("id-2"),
                        gtv("[0.12, 0.22, 0.32]"),
                        gtv("0.0"),
                        gtv(10),
                ),
                GtxOp("submit_query_request",
                        gtv("id-3"),
                        gtv("[0.1, 0.2, 0.3]"),
                        gtv("1.0"),
                        gtv(1),
                ),
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            getAndAssertSuccessfulComputation(node, "id-1") { result ->
                assertThat(result.result).isEqualTo(listOf(
                        QueryResultObject(1, 0, "0.00014106752753673124"),
                        QueryResultObject(2, 0, "0.0005202700678104133"),
                        QueryResultObject(3, 0, "0.0010824066508604568"),
                ))
            }

            getAndAssertSuccessfulComputation(node, "id-2") { result ->
                assertThat(result.result).isEqualTo(listOf(
                        QueryResultObject(2, 0, "0"),
                ))
            }

            getAndAssertSuccessfulComputation(node, "id-3") { result ->
                assertThat(result.result).isEqualTo(listOf(
                        QueryResultObject(1, 0, "0.00014106752753673124"),
                ))
            }
        }
    }

    @Test
    fun `safely remove while computing`() {
        val node = createNodes(3, "/chains/vector_example_query_compute_test.xml")[0]
        val messages = 50
        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction((1..messages).map {
            GtxOp("add_message", gtv("message $it"), gtv("[0.11, 0.21, 0.3${it}]"))
        }))

        // Initial state is 50 vectors
        assertThat(getVectors(node, DEFAULT_CHAIN_IID, "messages").filter { !it.exclude })
                .hasSize(messages)

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                // This will be removed directly since no computation is taken
                GtxOp("delete_vectors_safely", gtv("messages"), gtv(0), gtv(listOf(gtv(1)))),
                GtxOp("submit_query_request",
                        gtv("id-1"),
                        gtv("[0.1, 0.2, 0.3]"),
                        gtv("1.0"),
                        gtv(10),
                ),
                GtxOp("submit_query_request",
                        gtv("id-2"),
                        gtv("[0.12, 0.22, 0.32]"),
                        gtv("0.0"),
                        gtv(10),
                ),
                GtxOp("submit_query_request",
                        gtv("id-3"),
                        gtv("[0.1, 0.2, 0.3]"),
                        gtv("1.0"),
                        gtv(1),
                ),
        )))

        // After first block we have only removed one vector
        var vectors = getVectors(node, DEFAULT_CHAIN_IID, "messages")
        assertThat(vectors).hasSize(messages - 1)

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("submit_query_request",
                        gtv("id-4"),
                        gtv("[0.1, 0.2, 0.3]"),
                        gtv("1.0"),
                        gtv(10),
                ),
                // These will be scheduled to be removed after computations has completed
                GtxOp("delete_vectors_safely", gtv("messages"), gtv(0), gtv(listOf(gtv(2)))),
                GtxOp("delete_vectors_safely", gtv("messages"), gtv(0), gtv(listOf(gtv(3)))),
        )))

        // 2 vectors are scheduled to be removed and is exlucded from any query, still onlye one vector has been removed (in first block)
        assertThat(getSafeDeletes(node)).isEqualTo(listOf(
                SafeDelete("messages", 0, 2, listOf(2)),
                SafeDelete("messages", 0, 2, listOf(3)),
        ))
        vectors = getVectors(node, DEFAULT_CHAIN_IID, "messages")
        assertThat(vectors).hasSize(messages - 1)
        assertThat(vectors.filter { it.exclude }).hasSize(2)

        // Safely delete 4 & 5
        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("delete_vectors_safely", gtv("messages"), gtv(0), gtv(listOf(gtv(4), gtv(5)))),
        )))

        // Current state is 4 scheduled to be removed and excluded from search, still only 1 is removed
        assertThat(getSafeDeletes(node)).isEqualTo(listOf(
                SafeDelete("messages", 0, 2, listOf(2)),
                SafeDelete("messages", 0, 2, listOf(3)),
                SafeDelete("messages", 0, 3, listOf(4, 5))
        ))
        vectors = getVectors(node, DEFAULT_CHAIN_IID, "messages")
        assertThat(vectors).hasSize(messages - 1)
        assertThat(vectors.filter { it.exclude }).hasSize(4)

        // Await last computation
        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)
            getAndAssertSuccessfulComputation(node, "id-4") { }
        }

        // All safe deletions has been processed
        assertThat(getSafeDeletes(node)).isEmpty()

        // Vectors are updated in db, 5 removed in total, 0 excluded atm
        vectors = getVectors(node, DEFAULT_CHAIN_IID, "messages")
        assertThat(vectors).hasSize(messages - 5)
        assertThat(vectors.filter { it.exclude }).hasSize(0)

        // Remove one in the end without any running computation
        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                // This will be removed directly since no computation is taken
                GtxOp("delete_vectors_safely", gtv("messages"), gtv(0), gtv(listOf(gtv(6)))),
        )))
        assertThat(getVectors(node, DEFAULT_CHAIN_IID, "messages")).hasSize(messages - 6)
    }

    @Test
    fun `compute error`() {
        val node = createNodes(3, "/chains/vector_example_query_compute_test.xml")[0]

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("submit_query_request",
                        gtv("id-1"),
                        gtv("[a, b, c]"),
                        gtv("1.0"),
                        GtvNull,
                )
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            getAndAssertComputation(node, "id-1") { result ->
                assertThat(result?.error)
                        .isNotNull()
                        .isEqualTo("Vector is not correctly formatted")
            }
        }
    }

    fun getAndAssertComputation(node: PostchainTestNode, id: String, asserts: (QueryResult?) -> Unit) {
        val result = node.query(DEFAULT_CHAIN_IID) {
            it.query("get_query_result", gtv(mapOf("id" to gtv(id))))
        }

        if (result == null || result == GtvNull) {
            asserts(null)
        } else {
            asserts(GtvObjectMapper.fromGtv(result, QueryResult::class.java))
        }
    }

    fun getAndAssertSuccessfulComputation(node: PostchainTestNode, id: String, asserts: (QueryResult) -> Unit) {
        getAndAssertComputation(node, id) { result ->
            assertThat(result)
                    .isNotNull()
            assertThat(result!!.error).isNull()
            asserts(result)
        }
    }

    fun getSafeDeletes(node: PostchainTestNode): List<SafeDelete> {
        val result = node.query(DEFAULT_CHAIN_IID) {
            it.query("get_safe_deletes", gtv(mapOf()))
        }
        return result?.asArray()?.map {
            SafeDelete(it["collection"]!!.asString(), it["context"]!!.asInteger(),
                    it["height"]!!.asInteger(), it["ids"]!!.asArray().map { it.asInteger() })
        } ?: emptyList()
    }

    data class SafeDelete(val collection: String, val context: Long, val height: Long, val ids: List<Long>)
}
