package net.postchain.gtx.extensions.vectordb.embedding.client

import mu.KLogging
import net.postchain.common.exception.UserMistake
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine.Companion.X_API_KEY_HEADER
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingComputeConfig
import net.postchain.gtx.extensions.vectordb.config.VectorDbEmbeddingNodeConfig
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingRequest
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
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.GzipCompressionMode
import org.http4k.lens.basicAuthentication

/** Base client used for http requests against the embedding API */
abstract class BaseEmbeddingApiClient(
        var embeddingNodeConfig: VectorDbEmbeddingNodeConfig,
        connectTimeoutMs: Long,
        computeConfig: VectorDbEmbeddingComputeConfig
) : EmbeddingAPIClient {

    companion object : KLogging()

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

    /** Construct the request related to path, body etc. Authenticaiton is handled by this class */
    abstract fun buildRequest(request: EmbeddingRequest): Request

    /** Process response and create a generic EmbeddingResponse */
    abstract fun processResponse(httpResponse: Response): EmbeddingResponse

    override fun requestEmbeddings(request: EmbeddingRequest): EmbeddingResponse {
        val httpRequest = buildRequest(request)
                .let {
                    if (embeddingNodeConfig.basicAuth != null)
                        it.basicAuthentication(embeddingNodeConfig.basicAuth!!)
                    else it
                }

        var httpResponse: Response? = null
        repeat(embeddingNodeConfig.retryCount) {
            httpResponse = httpClient(httpRequest)

            when {
                isSuccess(httpResponse.status) -> return processResponse(httpResponse)

                isClientFailure(httpResponse.status) ->
                    throw UserMistake("Failed to request embedding: ${httpResponse.status}: " +
                            httpResponse.bodyString().take(200))

                // else retry
            }

            Thread.sleep(embeddingNodeConfig.retryDelay.inWholeMilliseconds)
        }

        throw UserMistake("Failed to request embedding (after ${embeddingNodeConfig.retryCount} retries): " +
                "${httpResponse?.status}: ${httpResponse?.bodyString()?.take(200)}")
    }

    fun isSuccess(status: Status) = status.successful

    fun isClientFailure(status: Status) = status == Status.BAD_REQUEST || status == Status.NOT_FOUND || status == Status.UNAUTHORIZED

}