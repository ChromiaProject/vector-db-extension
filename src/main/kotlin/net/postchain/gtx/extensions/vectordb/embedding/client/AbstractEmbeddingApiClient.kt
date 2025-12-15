package net.postchain.gtx.extensions.vectordb.embedding.client

import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine.Companion.X_API_KEY_HEADER
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingComputeConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingNodeConfig
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.HttpHeaders.AUTHORIZATION
import org.apache.hc.core5.util.Timeout
import org.http4k.client.ApacheClient
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.GzipCompressionMode

abstract class AbstractEmbeddingApiClient(
        var embeddingNodeConfig: VectorDbEmbeddingNodeConfig,
        connectTimeoutMs: Long,
        computeConfig: VectorDbEmbeddingComputeConfig
) : EmbeddingAPIClient {

    private val addRequestHeaders = Filter { next ->
        { request ->
            next(
                    request.let {
                        if (embeddingNodeConfig.authBearer != null)
                            it.header(AUTHORIZATION, "Bearer ${embeddingNodeConfig.authBearer}")
                        else it
                    }.let {
                        if (embeddingNodeConfig.xApiKey != null)
                            it.header(X_API_KEY_HEADER, embeddingNodeConfig.xApiKey)
                        else it
                    }
            )
        }
    }
    internal val httpClient: HttpHandler = addRequestHeaders
            .then(ClientFilters.AcceptGZip(GzipCompressionMode.Streaming())
                    .then(
                            ApacheClient(HttpClients.custom()
                                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                                            .setDefaultConnectionConfig(ConnectionConfig.custom()
                                                    .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                                                    .build())
                                            .build())
                                    .setDefaultRequestConfig(
                                            RequestConfig.custom()
                                                    .setRedirectsEnabled(false)
                                                    .setCookieSpec(StandardCookieSpec.IGNORE)
                                                    .setResponseTimeout(Timeout.ofSeconds(computeConfig.timeoutSeconds))
                                                    .build())
                                    .build())
                    ))
}