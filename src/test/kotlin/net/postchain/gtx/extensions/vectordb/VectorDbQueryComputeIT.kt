package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
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
            it.query("get_query_result", gtv(mapOf("id" to gtv(id), )))
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
}
