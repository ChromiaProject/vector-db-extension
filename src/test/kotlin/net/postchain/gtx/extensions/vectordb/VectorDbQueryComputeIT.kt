package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.query
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GtxOp
import net.postchain.gtx.extensions.vectordb.helpers.buildTransaction
import net.postchain.images.directory1.awaitUntilAsserted
import org.junit.jupiter.api.Test

class VectorDbQueryComputeIT : IntegrationTestSetup() {

    @Test
    fun `compute one query without template`() {
        val node = createNodes(3, "/chains/vector_example_compute_test.xml")[0]

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
                        GtvNull,
                        GtvNull
                ),
                GtxOp("submit_query_request",
                        gtv("id-2"),
                        gtv("[0.12, 0.22, 0.32]"),
                        gtv("0.0"),
                        gtv(10),
                        GtvNull,
                        GtvNull
                ),
                GtxOp("submit_query_request",
                        gtv("id-3"),
                        gtv("[0.1, 0.2, 0.3]"),
                        gtv("1.0"),
                        gtv(1),
                        GtvNull,
                        GtvNull
                ),
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            getAndAssertSuccessfulComputation(node, "id-1") { result ->
                assertThat(result[0]).isEqualTo(gtv(mapOf(
                        "id" to gtv(1),
                        "context" to gtv(0),
                        "distance" to gtv("0.00014106752753673124"),
                )))
                assertThat(result[1]).isEqualTo(gtv(mapOf(
                        "id" to gtv(2),
                        "context" to gtv(0),
                        "distance" to gtv("0.0005202700678104133"),
                )))
                assertThat(result[2]).isEqualTo(gtv(mapOf(
                        "id" to gtv(3),
                        "context" to gtv(0),
                        "distance" to gtv("0.0010824066508604568"),
                )))
            }

            getAndAssertSuccessfulComputation(node, "id-2") { result ->
                assertThat(result[0]).isEqualTo(gtv(mapOf(
                        "id" to gtv(2),
                        "context" to gtv(0),
                        "distance" to gtv("0"),
                )))
            }

            getAndAssertSuccessfulComputation(node, "id-3") { result ->
                assertThat(result[0]).isEqualTo(gtv(mapOf(
                        "id" to gtv(1),
                        "context" to gtv(0),
                        "distance" to gtv("0.00014106752753673124"),
                )))
            }
        }
    }

    @Test
    fun `compute multiple queries with template`() {
        val node = createNodes(3, "/chains/vector_example_compute_test.xml")[0]

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
                        gtv("get_messages"),
                        GtvNull
                )
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            getAndAssertSuccessfulComputation(node, "id-1") { result ->
                assertThat(result.asArray().map { it.asString() })
                        .isEqualTo(listOf("message 1", "message 2", "message 3"))
            }
        }
    }

    @Test
    fun `compute error`() {
        val node = createNodes(3, "/chains/vector_example_compute_test.xml")[0]

        buildBlock(DEFAULT_CHAIN_IID, node.buildTransaction(listOf(
                GtxOp("submit_query_request",
                        gtv("id-1"),
                        gtv("[a, b, c]"),
                        gtv("1.0"),
                        GtvNull,
                        GtvNull,
                        GtvNull
                )
        )))

        awaitUntilAsserted {
            buildBlock(DEFAULT_CHAIN_IID)

            getAndAssertComputation(node, "id-1") { result ->
                assertThat(result).isNotNull()
                assertThat(result).isNotEqualTo(GtvNull)
                assertThat(result!!["error"]).isNotNull()
            }
        }
    }

    fun getAndAssertComputation(node: PostchainTestNode, id: String, asserts: (Gtv?) -> Unit) {
        val result = node.query(DEFAULT_CHAIN_IID) {
            it.query("get_query_result", gtv(mapOf("id" to gtv(id), )))
        }

        asserts(result)
    }

    fun getAndAssertSuccessfulComputation(node: PostchainTestNode, id: String, asserts: (Gtv) -> Unit) {
        getAndAssertComputation(node, id) { result ->
            assertThat(result)
                    .isNotNull()
                    .isNotEqualTo(GtvNull)
            assertThat(result!!["error"]).isEqualTo(GtvNull)
            val gtvResult = result["result"]?.asDict()["result"]
            assertThat(gtvResult).isNotNull()
            asserts(gtvResult!!)
        }
    }
}
