package net.postchain.gtx.extensions.vectordb.helpers

import net.postchain.PostchainContext
import net.postchain.config.app.AppConfig
import net.postchain.core.BlockchainConfiguration
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingComputeConfig
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

object EmbeddingHelper {
    fun embeddingComputeEngine(model: String, nodeConfig: String): VectorDBEmbeddingComputeEngine {
        val configuration = mock<BlockchainConfiguration> {
            on { rawConfig } doReturn gtv(mapOf(
                    VectorDbGTXModule.VECTOR_DB_EXTENSION_CONFIG_NAME to
                            gtv(mapOf("embedding_compute" to GtvObjectMapper.toGtvDictionary(VectorDbEmbeddingComputeConfig(
                                    model,
                                    10
                            ))))
            ))
        }

        val service = VectorDBEmbeddingComputeEngine()

        val postchainContext = mock<PostchainContext> {
            on { appConfig } doReturn AppConfig.fromPropertiesFile(File(nodeConfig))
        }
        service.initializeContext(configuration, postchainContext, mock())

        return service
    }
}