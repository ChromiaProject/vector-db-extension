package net.postchain.gtx.extensions.vectordb.helpers

import net.postchain.gtx.extensions.vectordb.VLLMEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.VLLMEmbeddingResponseData
import net.postchain.gtx.extensions.vectordb.vLLMEmbeddingRequest
import net.postchain.gtx.extensions.vectordb.vLLMEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.vectorToBigDecimalList
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ServerFilters
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.SunHttp
import org.http4k.server.asServer
import java.io.Closeable

class MockEmbeddingRestApi : HttpHandler, Closeable {
    private var server: Http4kServer? = null
    var data = mapOf<String, Any>()

    private val app = ServerFilters.CatchLensFailure.then(
            routes(
                    "/v1/embeddings" bind Method.POST to { request ->
                        val embeddingRequest = vLLMEmbeddingRequest(request)

                        var embedding: String = if (data[embeddingRequest.input[0]]!! is MutableList<*>) {
                            (data[embeddingRequest.input[0]] as MutableList<*>).removeFirst() as String
                        } else {
                            data[embeddingRequest.input[0]]!! as String
                        }
                        val responseData = VLLMEmbeddingResponse("id", System.currentTimeMillis(), embeddingRequest.model, listOf(
                                VLLMEmbeddingResponseData(0, embedding.vectorToBigDecimalList())
                        ))

                        Response(Status.OK).with(vLLMEmbeddingResponse of responseData)
                    },
            )
    )

    override fun invoke(request: Request): Response = app(request)

    fun start() {
        server = app.asServer(SunHttp(0)).start()
    }

    fun port(): Int? {
        return server?.port()
    }

    override fun close() {
        server?.stop()
        server = null
    }
}
