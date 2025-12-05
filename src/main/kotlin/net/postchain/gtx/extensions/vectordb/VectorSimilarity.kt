package net.postchain.gtx.extensions.vectordb

import net.postchain.common.exception.ProgrammerMistake

object VectorSimilarity {

    fun cosineSimilarity(vectorA: DoubleArray, vectorB: DoubleArray): Double {
        require(vectorA.size == vectorB.size) {
            throw ProgrammerMistake("Vectors must have the same dimension: ${vectorA.size} vs ${vectorB.size}")
        }

        var dotProduct = 0.0
        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
        }
        return dotProduct
    }

    fun isSimilar(vectorA: DoubleArray, vectorV: DoubleArray, epsilon: Double): Pair<Boolean, Double> {
        val similarity = cosineSimilarity(vectorA, vectorV)
        return (similarity > 0 && 1 - similarity <= epsilon) to similarity
    }

    fun areAllSimilar(vectorsA: List<DoubleArray>, vectorB: List<DoubleArray>, epsilon: Double): Pair<Boolean, Double?> {
        vectorsA.forEachIndexed { index, list ->
            val similar = isSimilar(list, vectorB[index], epsilon)
            if (!similar.first) {
                return false to similar.second
            }
        }
        return true to null
    }
}
