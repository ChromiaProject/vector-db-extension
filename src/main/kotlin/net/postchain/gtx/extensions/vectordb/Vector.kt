package net.postchain.gtx.extensions.vectordb

import net.postchain.common.exception.UserMistake
import kotlin.text.removePrefix

fun isValidVectorFormat(vector: String): Boolean {
    if (!vector.startsWith("[") || !vector.endsWith("]"))
        return false

    return vector.substring(1, vector.length - 1).split(",").all {
        it.trim().toBigDecimalOrNull() != null
    }
}

fun isVectorExpectedDimensions(vector: String, dimensions: Int): Boolean {
    return vector.split(",").size == dimensions
}

fun requireValidVector(vector: String, expectedCollections: Int) {
    if (!isValidVectorFormat(vector)) {
        throw UserMistake("Vector is not correctly formatted")
    }
    if (!isVectorExpectedDimensions(vector, expectedCollections)) {
        throw UserMistake("Vector is expected to have $expectedCollections dimensions")
    }
}

fun String.vectorToList(): List<String> {
    return removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim() }
}
