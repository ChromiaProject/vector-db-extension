package net.postchain.gtx.extensions.vectordb.helpers

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * This is not vLLM, but the API for embeddings (and others?) look the same and the image size is small compared to
 * any vLLM image I found out there.
 */
open class TextEmbeddingsInferenceContainer : GenericContainer<TextEmbeddingsInferenceContainer>(
        DockerImageName.parse("ghcr.io/huggingface/text-embeddings-inference:cpu-sha-106d25f")
) {
    init {
        withExposedPorts(80)
        waitStrategy = LogMessageWaitStrategy()
                .withRegEx(".*http::server.*Ready.*\\s")
                .withTimes(1).withStartupTimeout(Duration.ofMinutes(1))
    }
}
