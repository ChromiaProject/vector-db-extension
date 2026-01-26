package net.postchain.gtx.extensions.vectordb

import assertk.assertFailure
import assertk.assertions.hasMessage
import assertk.assertions.isInstanceOf
import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.base.data.DatabaseAccess
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockEContext
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.Storage
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.CompositeGTXModule
import net.postchain.gtx.GTXModuleAware
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_EXTENSION_CONFIG_NAME
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule.Companion.VECTOR_DB_QUERY_CLOSEST_OBJECTS
import net.postchain.gtx.extensions.vectordb.config.VectorDbConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbQueryComputeConfig
import net.postchain.gtx.extensions.vectordb.lib.vector_db_query_compute.QueryRequest
import net.postchain.gtx.extensions.vectordb.lib.vector_db_query_compute.QueryResultObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.Thread.sleep
import java.util.concurrent.TimeoutException

class VectorDBQueryComputeEngineTest {

    companion object : KLogging()

    private var dba: VectorDbDatabaseAccess = mock()
    private var engine = VectorDBQueryComputeEngine(dba)
    private var configuration: BlockchainConfiguration = mock()
    private var postchainContext: PostchainContext = mock()
    private var bctx = mock<BlockEContext>()

    @BeforeEach
    fun beforeEach() {
        dba = mock<VectorDbDatabaseAccess>()
        engine = VectorDBQueryComputeEngine(dba)
        configuration = mock<BlockchainConfiguration>(extraInterfaces = arrayOf(GTXModuleAware::class)) {
            on { chainID } doReturn 100L
            on { hasQuery(eq(VECTOR_DB_QUERY_CLOSEST_OBJECTS)) } doReturn true
            on { rawConfig } doReturn gtv(mapOf(
                    VECTOR_DB_EXTENSION_CONFIG_NAME to GtvObjectMapper.toGtvDictionary(VectorDbConfig(mutableMapOf()))))
        }
        val vectorDbGtxModule = mock<VectorDbGTXModule>()
        val compositeGtxModule = mock<CompositeGTXModule> {
            on { modules } doReturn arrayOf(vectorDbGtxModule)
        }
        whenever((configuration as GTXModuleAware).module).thenReturn(compositeGtxModule)
        bctx = mock<BlockEContext> {
            on { getInterface<DatabaseAccess>(any()) } doReturn mock()
        }
        val sharedStorageMock = mock<Storage> {
            on { openReadConnection(anyLong()) } doReturn bctx
        }
        postchainContext = mock<PostchainContext> {
            on { sharedStorage } doReturn sharedStorageMock
        }
    }

    @Test
    fun `validation - successful`() {
        val qVector = "[0.1, 0.2, 0.3]"
        val collection = VectorCollection(1, "c1", 10, 20, 300,
                VectorDBIndex.HNSW_IP, VectorCollectionOrigin.DYNAMIC, true)
        val computeResults = listOf(QueryResultObject(1, 0, "0.0123"))
        val input = GtvObjectMapper.toGtvDictionary(QueryRequest("c1", qVector, 0.1.toBigDecimal(),
                null, null))

        whenever(dba.getExistingCollectionByName(any(), anyString())).doReturn(collection)
        whenever(dba.getDistanceOfResults(any(), eq(collection), eq(qVector),
                any())).doReturn(computeResults)

        engine.initializeContext(configuration, postchainContext, mock())

        val computeOutput = gtv(computeResults.map { GtvObjectMapper.toGtvDictionary(it) })
        engine.validate(bctx, input, computeOutput)
    }

    @Test
    fun `validation - successful - distance close enough`() {
        val qVector = "[0.1, 0.2, 0.3]"
        val collection = VectorCollection(1, "c1", 10, 20, 300,
                VectorDBIndex.HNSW_IP, VectorCollectionOrigin.DYNAMIC, true)
        val computeResults = listOf(QueryResultObject(1, 0, "0.1234567"))
        val localResults = listOf(QueryResultObject(1, 0, "0.1234568"))
        val input = GtvObjectMapper.toGtvDictionary(QueryRequest("c1", qVector, 0.2.toBigDecimal(),
                null, null))

        whenever(dba.getExistingCollectionByName(any(), anyString())).doReturn(collection)
        whenever(dba.getDistanceOfResults(any(), eq(collection), eq(qVector),
                any())).doReturn(localResults)

        engine.initializeContext(configuration, postchainContext, mock())

        val computeOutput = gtv(computeResults.map { GtvObjectMapper.toGtvDictionary(it) })
        engine.validate(bctx, input, computeOutput)
    }

    @Test
    fun `validation - reject - distance not close enough`() {
        val qVector = "[0.1, 0.2, 0.3]"
        val collection = VectorCollection(1, "c1", 10, 20, 300,
                VectorDBIndex.HNSW_IP, VectorCollectionOrigin.DYNAMIC, true)
        val computeResults = listOf(QueryResultObject(1, 0, "0.12345"))
        val localResults = listOf(QueryResultObject(1, 0, "0.123456"))
        val input = GtvObjectMapper.toGtvDictionary(QueryRequest("c1", qVector, 0.1.toBigDecimal(),
                null, null))

        whenever(dba.getExistingCollectionByName(any(), anyString())).doReturn(collection)
        whenever(dba.getDistanceOfResults(any(), eq(collection), eq(qVector),
                any())).doReturn(localResults)

        engine.initializeContext(configuration, postchainContext, mock())

        val computeOutput = gtv(computeResults.map { GtvObjectMapper.toGtvDictionary(it) })
        assertFailure {
            engine.validate(bctx, input, computeOutput)
        }.isInstanceOf(UserMistake::class)
                .hasMessage("Distance of some results exceeded max distance")
    }

    @Test
    fun `validation - reject - too long distance compared to input params`() {
        val qVector = "[0.1, 0.2, 0.3]"
        val collection = VectorCollection(1, "c1", 10, 20, 300,
                VectorDBIndex.HNSW_IP, VectorCollectionOrigin.DYNAMIC, true)
        val computeResults = listOf(QueryResultObject(1, 0, "0.3"))
        val input = GtvObjectMapper.toGtvDictionary(QueryRequest("c1", qVector, 0.1.toBigDecimal(),
                null, null))

        whenever(dba.getExistingCollectionByName(any(), anyString())).doReturn(collection)
        whenever(dba.getDistanceOfResults(any(), eq(collection), eq(qVector),
                any())).doReturn(computeResults)

        engine.initializeContext(configuration, postchainContext, mock())

        val computeOutput = gtv(computeResults.map { GtvObjectMapper.toGtvDictionary(it) })
        assertFailure {
            engine.validate(bctx, input, computeOutput)
        }.isInstanceOf(UserMistake::class)
                .hasMessage("Distance of some results exceeded max distance")
    }

    @Test
    fun `validation - reject - context do not match`() {
        val qVector = "[0.1, 0.2, 0.3]"
        val collection = VectorCollection(1, "c1", 10, 20, 300,
                VectorDBIndex.HNSW_IP, VectorCollectionOrigin.DYNAMIC, true)
        val computeResults = listOf(QueryResultObject(1, 0, "0.1"))
        val localResults = listOf(QueryResultObject(1, 1, "0.1"))
        val input = GtvObjectMapper.toGtvDictionary(QueryRequest("c1", qVector, 0.1.toBigDecimal(),
                null, null))

        whenever(dba.getExistingCollectionByName(any(), anyString())).doReturn(collection)
        whenever(dba.getDistanceOfResults(any(), eq(collection), eq(qVector),
                any())).doReturn(localResults)

        engine.initializeContext(configuration, postchainContext, mock())

        val computeOutput = gtv(computeResults.map { GtvObjectMapper.toGtvDictionary(it) })
        assertFailure {
            engine.validate(bctx, input, computeOutput)
        }.isInstanceOf(UserMistake::class)
                .hasMessage("Failed to verify result")
    }

    @Test
    fun `validation - reject - too many results in computed result`() {
        val qVector = "[0.1, 0.2, 0.3]"
        val collection = VectorCollection(1, "c1", 10, 20, 300,
                VectorDBIndex.HNSW_IP, VectorCollectionOrigin.DYNAMIC, true)
        val computeResults = listOf(
                QueryResultObject(1, 0, "0.3"),
                QueryResultObject(2, 0, "0.3"),
                QueryResultObject(3, 0, "0.3"),
        )
        val input = GtvObjectMapper.toGtvDictionary(QueryRequest("c1", qVector, 0.1.toBigDecimal(),
                null, 2))

        whenever(dba.getExistingCollectionByName(any(), anyString())).doReturn(collection)
        whenever(dba.getDistanceOfResults(any(), eq(collection), eq(qVector),
                any())).doReturn(computeResults)

        engine.initializeContext(configuration, postchainContext, mock())

        val computeOutput = gtv(computeResults.map { GtvObjectMapper.toGtvDictionary(it) })
        assertFailure {
            engine.validate(mock(), input, computeOutput)
        }.isInstanceOf(UserMistake::class)
                .hasMessage("Query returned more results than allowed")
    }

    @Test
    fun `validation - reject - timeout`() {
        val qVector = "[0.1, 0.2, 0.3]"
        val collection = VectorCollection(1, "c1", 10, 20, 300,
                VectorDBIndex.HNSW_IP, VectorCollectionOrigin.DYNAMIC, true)
        val computeResults = listOf(QueryResultObject(1, 0, "0.1"))
        val input = GtvObjectMapper.toGtvDictionary(QueryRequest("c1", qVector, 0.1.toBigDecimal(),
                null, null))

        whenever(dba.getExistingCollectionByName(any(), anyString())).doReturn(collection)
        whenever(dba.getDistanceOfResults(any(), eq(collection), eq(qVector),
                any())).doAnswer {
            sleep(2_000)
            emptyList()
        }
        whenever(configuration.rawConfig).doReturn(gtv(mapOf(
                VECTOR_DB_EXTENSION_CONFIG_NAME to GtvObjectMapper.toGtvDictionary(VectorDbConfig(mutableMapOf(),
                        VectorDbQueryComputeConfig(1))))))

        engine.initializeContext(configuration, postchainContext, mock())

        val computeOutput = gtv(computeResults.map { GtvObjectMapper.toGtvDictionary(it) })
        assertFailure {
            engine.validate(bctx, input, computeOutput)
        }.isInstanceOf(TimeoutException::class)
                .hasMessage("Query timed out after 1000 ms")
    }
}