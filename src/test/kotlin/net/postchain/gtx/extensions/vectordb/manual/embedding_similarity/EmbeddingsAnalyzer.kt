package net.postchain.gtx.extensions.vectordb.manual.embedding_similarity

import com.google.gson.Gson
import mu.KLogging
import net.postchain.gtx.extensions.vectordb.VLLMEmbeddingResponse
import net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine.Companion.DISTANCE_EPSILON
import net.postchain.gtx.extensions.vectordb.VectorSimilarity.isSimilar
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 1. Run [EmbeddingsFetcher] first to get data to analyze.
 * 2. Point embeddingsInputDir to the directory where EmbeddingsFetcher wrote the data.
 * 3. Run the test.
 */
@Disabled
class EmbeddingsAnalyzer {

    companion object : KLogging()

    private val embeddingInputDir = File("/home/joh-nils/chromaway/embeddings-test/")

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

                        Triple(service, hash, response.data[0].embedding)
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
        val maxDistanceBetweenServices = mutableMapOf<Pair<String, String>, Double>()

        val embeddingResultsByInput = embeddingRequests.groupBy { it.second }

        embeddingResultsByInput
                .mapValues { it.value.map { it.first to it.third } }
                .forEach { (hash, requests) ->
                    val serviceRequests = requests.groupBy { it.first }
                    val services = listOf(
                            "testnet", "cf", "gcp"
                    )
                    val resultsPerService = services
                            .associateWith { service -> serviceRequests[service]?.map { it.second } }
                            .filterValues { it != null }
                    val distinctResultsPerService = resultsPerService
                            .mapValues {
                                it.value!!.distinct()
                            }

                    distinctResultsPerService.forEach { (service, results) ->
                        if (results.size > 1) {
                            unstableResults.merge(service, 1, Int::plus)
                        }
                    }

                    logger.info("")
                    logger.info("Analyzing $hash - total: ${resultsPerService.mapValues { (k, v) -> v!!.size }} distinct: ${distinctResultsPerService.mapValues { (k, v) -> v.size }}")

                    val allPairSimilarity = distinctResultsPerService.keys.toList().allUnorderedPairs().map { pair ->

                        val similarity = getSimilarity(
                                distinctResultsPerService[pair.first]!!,
                                distinctResultsPerService[pair.second]!!
                                )

                        logger.info("  ${pair.first} vs ${pair.second}: $similarity")

                        val minSimilarity = similarity.minOfOrNull { it.second }!!
                        val distance = 1 - minSimilarity
                        maxDistance = maxOf(maxDistance, distance)
                        maxDistanceBetweenServices[pair] = maxOf(maxDistanceBetweenServices[pair] ?: 0.0, 1 - minSimilarity)

                        similarity
                    }.flatten()

                    with (allPairSimilarity.map { it.first }.toSet()) {
                        when {
                            size == 1 && first() -> similar++
                            size == 1 && !first() -> notSimilar++
                            else -> partialSimilar++
                        }
                    }


//
//                    if (serviceTestnet != null && serviceB != null) {
//
//                        val distinctTestnetResponses = serviceTestnet.map { it.data[0] }.distinct()
//                        val distinctCFResponses = serviceB.map { it.data[0] }.distinct()
//
//                        if (distinctTestnetResponses.size > 1) {
//                            unstableResults.merge("testnet", 1, Int::plus)
//
//                            val testnetSimilarity = distinctTestnetResponses.map { testnetResponse ->
//                                val others = distinctTestnetResponses.toMutableList()
//                                others.remove(testnetResponse)
//                                getSimilarity(
//                                        listOf(testnetResponse),
//                                        others)
//                                        .map { it.second }
//                            }.flatten().distinct()
//
//                            testnetUnstableResultMinDistance = min(testnetUnstableResultMinDistance, 1 - testnetSimilarity.max())
//                            testnetUnstableResultMaxDistance = max(testnetUnstableResultMaxDistance, 1 - testnetSimilarity.min())
//                        }
//                        if (distinctCFResponses.size > 1) {
//                            unstableResults.merge("cf", 1, Int::plus)
//                        }
//
//                        val similarity = getSimilarity(distinctTestnetResponses, distinctCFResponses)
//
//                        logger.info(similarity.toString())
//
//                        with (similarity.map { it.first }.toSet()) {
//                            when {
//                                size == 1 && first() -> similar++
//                                size == 1 && !first() -> notSimilar++
//                                else -> partialSimilar++
//                            }
//                        }
//
//                        val minSimilarity = similarity.minOfOrNull { it.second }!!
//                        maxDistance = maxOf(maxDistance, 1 - minSimilarity)
//                    }
                }

        logger.info("")
        logger.info("Analyzed ${embeddingRequests.size} responses for ${embeddingResultsByInput.size} inputs")
        logger.info("Max distance between services: ${maxDistanceBetweenServices.mapValues { (k, v) -> v.toBigDecimal().toPlainString() }}")
        logger.info("Similar: $similar, not similar: $notSimilar, partial similar: $partialSimilar, max distance: ${maxDistance.toBigDecimal().toPlainString()}")
        logger.info("Unstable results: $unstableResults")

//        logger.info("Testnet unstable min: ${testnetUnstableResultMinDistance.toBigDecimal().toPlainString()}  max: ${testnetUnstableResultMaxDistance.toBigDecimal().toPlainString()}")
    }

    private fun getSimilarity(embeddingsA: List<List<String>>, embeddingsB: List<List<String>>): List<Pair<Boolean, Double>> {
        val similarity = embeddingsA.map { a ->
            embeddingsB.map { b ->
                isSimilar(
                        a.map { it.toDouble() }.toDoubleArray(),
                        b.map { it.toDouble() }.toDoubleArray(),
                        DISTANCE_EPSILON)
            }
        }.flatten()
        return similarity
    }

    fun <T> List<T>.allUnorderedPairs(): List<Pair<T, T>> =
            flatMapIndexed { i, a ->
                (i + 1 until size).map { j -> a to this[j] }
            }
}
