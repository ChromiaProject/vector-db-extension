package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.common.exception.UserMistake
import net.postchain.core.EContext
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.extensions.vectordb.config.VectorDBIndex
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

class VectorDbGTXModuleTest {

    open class TestEContext(override val id: String = "1") : EContext {
        override val chainID: Long = -1
        override val conn: Connection = mock()
    }

    private val defaultArgs = mapOf(
            "collection" to gtv("collection1"),
            "context" to gtv(0),
            "q_vector" to gtv("[1, 2, 3]"),
            "max_distance" to gtv("0.1"),
    )

    private var dbaMock = mock<VectorDbDatabaseAccess>()
    private var context: VectorDbGTXModuleContext = VectorDbGTXModuleContext(dbaMock)

    @BeforeEach
    fun beforeEach() {
        dbaMock = mock()
        context = VectorDbGTXModuleContext(dbaMock)
        context.module = VectorDbGTXModule()
        context.collectionsByName = ConcurrentHashMap(mapOf("collection1" to VectorCollection(
                0, "collection1", 100, 10, 300, VectorDBIndex.HNSW_COSINE
        )))
        context.postchainContext = mock()
        context.vectorDbConfig = mock()
    }

    @Test
    fun `collection - missing`() {
        assertThat(assertThrows<UserMistake> {
            VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                    gtv(mapOf("q_vector" to gtv("[1, 2, 3]"))))
        }.message).isEqualTo("No collection argument supplied")
    }

    @Test
    fun `collection - not existing`() {
        assertThat(assertThrows<UserMistake> {
            VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                    gtv(mapOf("collection" to gtv("cats"), "q_vector" to gtv("[1, 2, 3]"))))
        }.message).isEqualTo("Collection cats not found")
    }

    @Test
    fun `max vectors - too big`() {

        // More than limit set in blockchain config - reject
        assertThat(assertThrows<UserMistake> {
            VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                    gtv(defaultArgs + mapOf("query_max_vectors" to gtv(11L))))
        }.message).isEqualTo("query_max_vectors (11) exceeds the maximum of 10")
    }

    @Test
    fun `max vectors - same as limit`() {

        // Max length - accepted - the exception is OK, it passed the validation
        VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                gtv(defaultArgs + mapOf("query_max_vectors" to gtv(10L))))
        verify(dbaMock, times(1)).queryClosestObjects(any(), any(),
                eq(0), eq("[1, 2, 3]"),
                eq(0.1.toBigDecimal()), eq(10L), any())
    }

    @Test
    fun `max vectors - default`() {
        // Default value - accepted - the exception is OK, it passed the validation
        VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                gtv(defaultArgs))
        verify(dbaMock, times(1)).queryClosestObjects(any(), any(),
                eq(0), eq("[1, 2, 3]"),
                eq(0.1.toBigDecimal()), eq(10L), any())
    }
}
