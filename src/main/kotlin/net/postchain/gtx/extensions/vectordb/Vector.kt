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

fun isVectorExpectedDimensions(vector: String, dimensions: Long): Boolean {
    return vector.split(",").size.toLong() == dimensions
}

fun requireValidVector(vector: String, expectedDimensions: Long) {
    if (!isValidVectorFormat(vector)) {
        throw UserMistake("Vector is not correctly formatted")
    }
    if (!isVectorExpectedDimensions(vector, expectedDimensions)) {
        throw UserMistake("Vector is expected to have $expectedDimensions dimensions")
    }
}

fun String.vectorToList(): List<String> {
    return removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim() }
}

fun List<String>.listToVector(): String {
    return joinToString(",", "[", "]")
}
