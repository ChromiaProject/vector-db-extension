package net.postchain.gtx.extensions.vectordb.manual.embedding_similarity

import com.google.gson.Gson
import mu.KLogging
import net.postchain.gtx.extensions.vectordb.VLLMEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.VLLMEmbeddingResponseData
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine.Companion.DISTANCE_EPSILON
import net.postchain.gtx.extensions.vectordb.VectorSimilarity.isSimilar
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 1. Run [EmbeddingsFetcher] first to get data to analyze.
 * 2. Point embeddingsInputDir to the directory where EmbeddingsFetcher wrote the data.
 * 3. Run the test.
 */
@Disabled
class EmbeddingsAnalyzer {

    companion object : KLogging()

    private val embeddingInputDir = File("/home/joh-nils/chromaway/embeddings-test/manual/")

    val walkPattern = "${embeddingInputDir.absolutePath}/.*/.*[.]json".toRegex()
    val filePattern = "([^_]+)_([^_]+)+_\\d+.json".toRegex()
    val gson = Gson()

    @Test
    fun analyzeEmbeddings() {

        val embeddingRequests = embeddingInputDir.walk()
                .filter { it.isFile }
                .filter {
                    walkPattern.matches(it.absolutePath)
                }
                .toList()
                .parallelStream()
                .map { file ->
                    filePattern.find(file.name)?.groupValues?.let {
                        val service = it[1]
                        val hash = it[2]

                        val response = gson.fromJson(file.readText(), VLLMEmbeddingResponse::class.java)

                        Triple(service, hash, response)
                    }
                }
                .toList()
                .filterNotNull()

        logger.info("Loaded ${embeddingRequests.size} responses.")

        var similar = 0
        var notSimilar = 0
        var partialSimilar = 0
        var maxDistance = 0.0
        val unstableResults = mutableMapOf<String, Int>()
        var testnetUnstableResultMinDistance = Double.MAX_VALUE
        var testnetUnstableResultMaxDistance = 0.0

        val embeddingResultsByInput = embeddingRequests.groupBy { it.second }

        embeddingResultsByInput
                .mapValues { it.value.map { it.first to it.third } }
                .forEach { (hash, requests) ->
                    val serviceRequests = requests.groupBy { it.first }
                    val testnet = serviceRequests["testnet"]?.map { it.second }
                    val cf = serviceRequests["cf"]?.map { it.second }

                    logger.info("")
                    logger.info("Analyzing $hash - testnet: ${serviceRequests["testnet"]?.size}  cf: ${serviceRequests["cf"]?.size}")

                    if (testnet != null && cf != null) {

                        val distinctTestnetResponses = testnet.map { it.data[0] }.distinct()
                        val distinctCFResponses = cf.map { it.data[0] }.distinct()

                        if (distinctTestnetResponses.size > 1) {
                            unstableResults.merge("testnet", 1, Int::plus)

                            val testnetSimilarity = distinctTestnetResponses.map { testnetResponse ->
                                val others = distinctTestnetResponses.toMutableList()
                                others.remove(testnetResponse)
                                getSimilarity(
                                        listOf(testnetResponse),
                                        others)
                                        .map { it.second }
                            }.flatten().distinct()

                            testnetUnstableResultMinDistance = min(testnetUnstableResultMinDistance, 1 - testnetSimilarity.max())
                            testnetUnstableResultMaxDistance = max(testnetUnstableResultMaxDistance, 1 - testnetSimilarity.min())
                        }
                        if (distinctCFResponses.size > 1) {
                            unstableResults.merge("cf", 1, Int::plus)
                        }

                        val similarity = getSimilarity(distinctTestnetResponses, distinctCFResponses)

                        logger.info(similarity.toString())

                        with (similarity.map { it.first }.toSet()) {
                            when {
                                size == 1 && first() -> similar++
                                size == 1 && !first() -> notSimilar++
                                else -> partialSimilar++
                            }
                        }

                        val minSimilarity = similarity.minOfOrNull { it.second }!!
                        maxDistance = maxOf(maxDistance, 1 - minSimilarity)
                    }
                }

        logger.info("")
        logger.info("Analyzed ${embeddingRequests.size} responses for ${embeddingResultsByInput.size} inputs")
        logger.info("Similar: $similar, not similar: $notSimilar, partial similar: $partialSimilar, max distance: ${maxDistance.toBigDecimal().toPlainString()}")
        logger.info("Unstable results: $unstableResults")
        logger.info("Testnet unstable min: ${testnetUnstableResultMinDistance.toBigDecimal().toPlainString()}  max: ${testnetUnstableResultMaxDistance.toBigDecimal().toPlainString()}")
    }

    private fun getSimilarity(responseA: List<VLLMEmbeddingResponseData>, responseB: List<VLLMEmbeddingResponseData>): List<Pair<Boolean, Double>> {
        val similarity = responseA.map { a ->
            responseB.map { b ->
                isSimilar(
                        a.embedding.map { it.toDouble() }.toDoubleArray(),
                        b.embedding.map { it.toDouble() }.toDoubleArray(),
                        DISTANCE_EPSILON)
            }
        }.flatten()
        return similarity
    }
}
