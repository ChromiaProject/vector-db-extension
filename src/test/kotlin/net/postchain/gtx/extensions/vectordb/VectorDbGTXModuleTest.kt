package net.postchain.gtx.extensions.vectordb

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import net.postchain.common.exception.UserMistake
import net.postchain.core.EContext
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXBlockchainConfiguration
import net.postchain.gtx.extensions.vectordb.config.VectorDbCollectionConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

class VectorDbGTXModuleTest {

    open class TestEContext(override val id: String = "1") : EContext {
        override val chainID: Long = -1
        override val conn: Connection = mock()
    }

    private val collection1 = VectorCollection(0, "collection1", 3, 10, 300, VectorDBIndex.HNSW_COSINE, VectorCollectionOrigin.DYNAMIC, true)

    private val defaultArgs = mapOf(
            "collection" to gtv(collection1.name),
            "context" to gtv(0),
            "q_vector" to gtv("[1, 2, 3]"),
            "max_distance" to gtv("0.1"),
    )

    private var module = VectorDbGTXModule(mock())
    private var dbaMock = mock<VectorDbDatabaseAccess>()
    private var context: VectorDbGTXModuleContext = VectorDbGTXModuleContext(dbaMock)

    @BeforeEach
    fun beforeEach() {
        dbaMock = mock()
        module = VectorDbGTXModule(dbaMock)
        context = module.conf
        context.module = module
        context.collectionOriginMode = VectorCollectionOrigin.STATIC
        context.collectionsByName = ConcurrentHashMap(mapOf("collection1" to VectorCollection(
                0, collection1.name, 3, 10, 300, VectorDBIndex.HNSW_COSINE, VectorCollectionOrigin.STATIC, true
        )))
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

    @Test
    fun `collections - origin modes - static can't started when dynamics exists`() {
        `when`(dbaMock.getCollectionOrigins(any())).doReturn(setOf(VectorCollectionOrigin.DYNAMIC))
        `when`(dbaMock.getExistingCollections(any())).doReturn(emptyMap())

        val configuration = mock<GTXBlockchainConfiguration> {
            on { module } doReturn context.module
            on { rawConfig } doReturn gtv(mapOf(
                    VectorDbGTXModule.VECTOR_DB_EXTENSION_CONFIG_NAME to GtvObjectMapper.toGtvDictionary(VectorDbConfig(
                            collections = mapOf(
                                    collection1.name to VectorDbCollectionConfig(3, 10, 300, VectorDBIndex.HNSW_COSINE.name)
                            )
                    ))
            ))
        }
        assertFailure {
            module.initializeContext(configuration, mock(), mock())
        }.isInstanceOf(UserMistake::class)
                .messageContains("Database initialized with static collections, but dynamic collections exist in DB")
    }

    @Test
    fun `reject config with new distance type`() {
        `when`(dbaMock.getCollectionOrigins(any())).doReturn(setOf(VectorCollectionOrigin.STATIC))
        `when`(dbaMock.getExistingCollections(any())).doReturn(mapOf(collection1.name to collection1))

        val configuration = mock<GTXBlockchainConfiguration> {
            on { module } doReturn context.module
            on { rawConfig } doReturn gtv(mapOf(
                    VectorDbGTXModule.VECTOR_DB_EXTENSION_CONFIG_NAME to GtvObjectMapper.toGtvDictionary(VectorDbConfig(
                            collections = mapOf(
                                    "collection1" to VectorDbCollectionConfig(3, 10, 20, VectorDBIndex.HNSW_IP.name)
                            )
                    ))
            ))
        }
        assertFailure {
            module.initializeContext(configuration, mock(), mock())
        }.isInstanceOf(UserMistake::class)
                .messageContains("Changing embedded index is not supported for collection collection1")
    }

    @Test
    fun `reject config with new dimension`() {
        `when`(dbaMock.getCollectionOrigins(any())).doReturn(setOf(VectorCollectionOrigin.STATIC))
        `when`(dbaMock.getExistingCollections(any())).doReturn(mapOf(collection1.name to collection1))

        val configuration = mock<GTXBlockchainConfiguration> {
            on { module } doReturn context.module
            on { rawConfig } doReturn gtv(mapOf(
                    VectorDbGTXModule.VECTOR_DB_EXTENSION_CONFIG_NAME to GtvObjectMapper.toGtvDictionary(VectorDbConfig(
                            collections = mapOf(
                                    "collection1" to VectorDbCollectionConfig(30, 10, 20, VectorDBIndex.HNSW_COSINE.name)
                            )
                    ))
            ))
        }
        assertFailure {
            module.initializeContext(configuration, mock(), mock())
        }.isInstanceOf(UserMistake::class)
                .messageContains("Changing dimensions is not supported for collection collection1")
    }
}
