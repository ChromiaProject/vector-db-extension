package net.postchain.gtx.extensions.vectordb.manual.embedding_similarity

import com.google.gson.Gson
import mu.KLogging
import net.postchain.gtx.extensions.vectordb.VLLMEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine
import net.postchain.gtx.extensions.vectordb.helpers.EmbeddingHelper.embeddingComputeEngine
import net.postchain.gtx.extensions.vectordb.lib.vector_db_embedding_compute.EmbeddingRequest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.random.Random

/**
 * 1. Download https://www.kaggle.com/datasets/ffatty/plain-text-wikipedia-simpleenglish?resource=download and point testSource to it
 * 2. Set output directory in embeddingTestDir
 * 3. Create and configure testnetConfig and cfConfig. Add the extension url properties as in a node config.
 * 4. Set startOffset and workCount to select input text segments.
 * 4. Run the test
 */
@Disabled
class EmbeddingsFetcher {

    companion object : KLogging()

    // https://www.kaggle.com/datasets/ffatty/plain-text-wikipedia-simpleenglish?resource=download
    private val textSource = File("/home/joh-nils/Downloads/AllCombined.txt.zip")
    private val embeddingTestDir = File("/home/joh-nils/chromaway/embeddings-test/150/")
    private val testnetConfig = "/home/joh-nils/chromaway/embeddings-test/testnet.properties"
    private val cfConfig = "/home/joh-nils/chromaway/embeddings-test/cf.properties"
    private val startOffset = 950
    private val workCount = 200
    private val gson = Gson()

    @Test
    fun run() {

        val executor = Executors.newFixedThreadPool(8)
        val serviceA = embeddingComputeEngine("qwen3-embedding-0.6b", testnetConfig)
        val serviceB = embeddingComputeEngine("@cf/qwen/qwen3-embedding-0.6b", cfConfig)

        sequentialTextSegmentsStream(minWords = 150, maxWords = 150)
                .drop(startOffset)
            .take(workCount)
            .forEach { segment ->
                val hash = segment.hashCode().toString().replace('-', 'n')

                val inputFile = embeddingTestDir.resolve("${hash}/input")
                if (!inputFile.exists()) {
                    inputFile.parentFile.mkdirs()
                    inputFile.writeText(gson.toJson(segment))
                }

                executor.submit(createEmbeddingRequestTask(serviceA, "testnet", hash, segment))
                executor.submit(createEmbeddingRequestTask(serviceB, "cf", hash, segment))
            }

        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.DAYS)
    }

    private fun createEmbeddingRequestTask(serviceA: VectorDBEmbeddingComputeEngine, service: String, hash: String, text: String) = Runnable {
        try {
            val response = serviceA.requestEmbeddings(EmbeddingRequest(listOf(text)))
            writeEmbedding(service, hash, response)
        } catch (e: Exception) {
            logger.error(e) { "Error fetching embeddings for $text" }
        }
    }

    private fun writeEmbedding(service: String, hash: String, responseA: VLLMEmbeddingResponse) {
        (0..20).map { embeddingTestDir.resolve("${hash}/${service}_${hash}_$it.json") }
                .first { !it.exists() }
                .writeText(gson.toJson(responseA))
    }

    fun sequentialTextSegmentsStream(
        minWords: Int = 150,
        maxWords: Int = 250,
        includeLastPartial: Boolean = true
    ): Sequence<String> {
        require(minWords > 0) { "minWords must be > 0" }
        require(maxWords >= minWords) { "maxWords must be >= minWords" }
        require(textSource.exists() && textSource.isFile) {
            "Text source not found: ${textSource.absolutePath}"
        }

        return sequence {
            FileInputStream(textSource).use { fis ->
                ZipInputStream(fis).use { zis ->
                    zis.nextEntry
                    val reader = BufferedReader(InputStreamReader(zis))

                    val rng = Random
                    var target = rng.nextInt(minWords, maxWords + 1)

                    val current = ArrayList<String>(maxWords)
                    val sb = StringBuilder()

                    fun flushWord() {
                        if (sb.isNotEmpty()) {
                            current.add(sb.toString())
                            sb.setLength(0)
                        }
                    }

                    var code = reader.read()
                    while (code != -1) {
                        val ch = code.toChar()
                        if (ch.isWhitespace()) {
                            if (sb.isNotEmpty()) {
                                flushWord()
                                if (current.size >= target) {
                                    yield(current.joinToString(" "))
                                    current.clear()
                                    target = rng.nextInt(minWords, maxWords + 1)
                                }
                            }
                        } else {
                            sb.append(ch)
                        }
                        code = reader.read()
                    }

                    flushWord()

                    if (current.isNotEmpty()) {
                        if (current.size >= minWords || includeLastPartial) {
                            yield(current.joinToString(" "))
                        }
                    }
                }
            }
        }
    }
}