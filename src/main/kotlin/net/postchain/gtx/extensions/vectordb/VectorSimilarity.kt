package net.postchain.gtx.extensions.vectordb

import net.postchain.common.exception.ProgrammerMistake

object VectorSimilarity {

    /**
     * Calculates the cosine similarity between two vectors. We assume normalized vectors.
     * @return The similarity.
     * @throws ProgrammerMistake if vectors have different dimensions.
     */
    fun cosineSimilarity(vectorA: DoubleArray, vectorB: DoubleArray): Double {
        if (vectorA.size != vectorB.size) {
            throw ProgrammerMistake("Vectors must have the same dimension: ${vectorA.size} vs ${vectorB.size}")
        }

        var dotProduct = 0.0
        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
        }
        return dotProduct
    }

    /** Compares two vectors for similarity.
     * @return The similarity result.
     * @throws ProgrammerMistake if vectors have different dimensions.
     */
    fun isSimilar(vectorA: DoubleArray, vectorV: DoubleArray, epsilon: Double): SimilarityResult {
        val similarity = cosineSimilarity(vectorA, vectorV)
        return SimilarityResult(similarity > 0 && 1 - similarity <= epsilon, similarity)
    }

    /**
     * Compares all vectors in a list with a single vector for similarity.
     * @return The similarity result, if not similar the similarity value of the first none similar vector is returned.
     * @throws ProgrammerMistake if vectors have different dimensions.
     */
    fun areAllSimilar(vectorsA: List<DoubleArray>, vectorsB: List<DoubleArray>, epsilon: Double): SimilarityResult {
        if (vectorsA.size != vectorsB.size) {
            throw ProgrammerMistake("List of vectors must have the same size: ${vectorsA.size} vs ${vectorsB.size}")
        }
        vectorsA.forEachIndexed { index, list ->
            val similar = isSimilar(list, vectorsB[index], epsilon)
            if (!similar.isSimilar) {
                return similar
            }
        }
        return SimilarityResult(true, null)
    }
}

data class SimilarityResult(
        val isSimilar: Boolean,
        val similarity: Double?
)
