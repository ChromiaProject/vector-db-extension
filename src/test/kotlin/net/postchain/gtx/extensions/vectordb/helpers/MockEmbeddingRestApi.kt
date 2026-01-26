package net.postchain.gtx.extensions.vectordb.helpers

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine.Companion.X_API_KEY_HEADER
import net.postchain.gtx.extensions.vectordb.embedding.client.OpenAiEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.embedding.client.OpenAiEmbeddingResponseData
import net.postchain.gtx.extensions.vectordb.embedding.client.openAiEmbeddingRequest
import net.postchain.gtx.extensions.vectordb.embedding.client.openAiEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.vectorToList
import org.apache.hc.core5.http.HttpHeaders.AUTHORIZATION
import org.http4k.core.Credentials
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ServerFilters
import org.http4k.lens.basicAuthentication
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.SunHttp
import org.http4k.server.asServer
import java.io.Closeable

/**
 * Mock a openAI embedding rest api service.
 */
class MockEmbeddingRestApi(
        val model: String,
        val basicAuth: Credentials? = null,
        val authBearer: String? = null,
        val xApiKey: String? = null
) : HttpHandler, Closeable {
    private var server: Http4kServer? = null
    var data = mapOf<String, Any>()

    private val app: HttpHandler
    private val missingAuthBearerStatus = Status(401, "Missing bearer")
    private val missingXApiKeyStatus = Status(401, "Missing x-api-key")

    val url by lazy {
        "http://localhost:${port()}"
    }

    init {
        val routesApp = routes(
                "/v1/embeddings" bind Method.POST to { request ->
                    val embeddingRequest = openAiEmbeddingRequest(request)

                    if (embeddingRequest.model != model) {
                        Response(Status.NOT_FOUND)
                    } else {

                        val embedding: String = if (data[embeddingRequest.input[0]]!! is MutableList<*>) {
                            (data[embeddingRequest.input[0]] as MutableList<*>).removeFirst() as String
                        } else {
                            data[embeddingRequest.input[0]]!! as String
                        }
                        val responseData = OpenAiEmbeddingResponse(
                                "id",
                                System.currentTimeMillis(),
                                embeddingRequest.model,
                                listOf(OpenAiEmbeddingResponseData(0, embedding.vectorToList()))
                        )

                        Response(Status.OK).with(openAiEmbeddingResponse of responseData)
                    }
                },
        )

        var maybeSecuredApp: HttpHandler = routesApp
                .let {
                    if (basicAuth != null) {
                        ServerFilters.BasicAuth("mock-embeddings", basicAuth).then(it)
                    } else it
                }
                .let {
                    if (authBearer != null) {
                        headerFilter(AUTHORIZATION, "Bearer $authBearer", missingAuthBearerStatus).then(it)
                    } else it
                }
                .let {
                    if (xApiKey != null) {
                        val then = headerFilter(X_API_KEY_HEADER, xApiKey, missingXApiKeyStatus).then(it)
                        then
                    } else it
                }

        app = ServerFilters.CatchLensFailure.then(maybeSecuredApp)
    }

    private fun headerFilter(name: String, value: String, status: Status): Filter {
        val bearerFilter = Filter { next: HttpHandler ->
            { req: Request ->
                if (req.headerValues(name).any { it == value }) {
                    next(req)
                } else {
                    Response(status)
                }
            }
        }
        return bearerFilter
    }

    override fun invoke(request: Request): Response = app(request)

    fun start() {
        selfAuthorizationTest()
        server = app.asServer(SunHttp(0)).start()
    }

    fun port(): Int? {
        return server?.port()
    }

    fun selfAuthorizationTest() {
        if (basicAuth != null) {
            assertThat(app.invoke(Request(Method.GET, "http://localhost/self-test")).status).isEqualTo(Status.UNAUTHORIZED)
        }
        if (authBearer != null) {
            assertThat(app.invoke(Request(Method.GET, "http://localhost/self-test").let {
                if (basicAuth != null) it.basicAuthentication(basicAuth) else it
            }).status).isEqualTo(missingAuthBearerStatus)
        }
        if (xApiKey != null) {
            assertThat(app.invoke(Request(Method.GET, "http://localhost/self-test").let {
                if (basicAuth != null) it.basicAuthentication(basicAuth) else it
            }.let {
                if (authBearer != null) it.header(AUTHORIZATION, "Bearer $authBearer") else it
            }).status).isEqualTo(missingAuthBearerStatus)
        }

        assertThat(app.invoke(Request(Method.GET, "http://localhost/self-test").let {
            if (basicAuth != null) it.basicAuthentication(basicAuth) else it
        }.let {
            if (authBearer != null) it.header(AUTHORIZATION, "Bearer $authBearer") else it
        }.let {
            if (xApiKey != null) it.header(X_API_KEY_HEADER, xApiKey) else it
        }).status).isEqualTo(Status.NOT_FOUND)
    }

    override fun close() {
        server?.stop()
        server = null
    }
}
