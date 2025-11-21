package net.postchain.gtx.extensions.vectordb

import assertk.assertFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingComputeConfig
import net.postchain.gtx.extensions.vectordb.hc.lib.vector_db_embedding_compute.EmbeddingRequest
import net.postchain.hybridcompute.HybridComputeEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.TimeUnit

class VectorDBEmbeddingComputeEngineTest {

    val unroutableInternetUrl = "http://10.255.255.1:1"
    val input = GtvObjectMapper.toGtvDictionary(EmbeddingRequest(gtv(mapOf(
            "model" to gtv("model"),
            "input" to gtv(listOf(gtv("text1")))
    ))))

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `connect timeout compute`() {
        val engine = createEngine(unroutableInternetUrl)
        assertFailure {
            engine.compute(input)
        }.isInstanceOf(ProgrammerMistake::class)
                .messageContains("Client Timeout caused by Connect to")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `connect timeout validate`() {
        val engine = createEngine(unroutableInternetUrl)
        assertFailure {
            engine.validate(input, gtv("output"))
        }.isInstanceOf(ProgrammerMistake::class)
                .messageContains("Client Timeout caused by Connect to")
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `read timeout compute`() {
        withRequestTimeoutServer { url ->
            val engine = createEngine(url)
            assertFailure {
                engine.compute(input)
            }.isInstanceOf(ProgrammerMistake::class)
                    .messageContains("Client Timeout caused by Read timed out")
        }
    }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `read timeout validate`() {
        withRequestTimeoutServer { url ->
            val engine = createEngine(url)
            assertFailure {
                engine.validate(input, gtv("output"))
            }.isInstanceOf(ProgrammerMistake::class)
                    .messageContains("Client Timeout caused by Read timed out")
        }
    }

    private fun withRequestTimeoutServer(block: (url: String) -> Unit) {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { serverSocketChannel ->
            serverSocketChannel.configureBlocking(false)
            serverSocketChannel.bind(null)
            serverSocketChannel.accept()
            val localAddress = (serverSocketChannel.localAddress as InetSocketAddress)
            val url = "http://${localAddress.hostName}:${localAddress.port}"
            block(url)
        }
    }

    private fun createEngine(url: String, connectTimeout: Long = 100): HybridComputeEngine {
        val engine = VectorDBEmbeddingComputeEngine(connectTimeout)
        val postchainContext = mock<PostchainContext> {
            on { appConfig } doReturn AppConfig.fromEnvironment(mapOf(
                    "extension.vector_db.embedding.url" to url
            ))
        }
        val embeddingConfig = VectorDbEmbeddingComputeConfig(
                "test-model",
                2
        )
        val configuration = mock<BlockchainConfiguration> {
            on { rawConfig } doReturn gtv(mapOf(
                VectorDbGTXModule.VECTOR_DB_EXTENSION_CONFIG_NAME to
                    gtv(mapOf("embedding_compute" to GtvObjectMapper.toGtvDictionary(embeddingConfig)))
            ))
        }
        engine.initializeContext(configuration, postchainContext, mock())
        return engine
    }
}