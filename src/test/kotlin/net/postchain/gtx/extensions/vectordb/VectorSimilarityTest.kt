package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class VectorSimilarityTest {

    @Test
    fun testSimilarity() {
        val vectorSimilarity = VectorSimilarity()

        assertThat(vectorSimilarity.isSimilar(
                doubleArrayOf(0.0, 1.0),
                        doubleArrayOf(0.0, 1.0),
                0.0).first).isTrue()
        assertThat(vectorSimilarity.isSimilar(
                doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(4.0, 5.0, 6.0),
                0.0).first).isFalse()
        assertThat(vectorSimilarity.isSimilar(
                doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(8.0, 7.0, 6.0),
                0.0).first).isFalse()
        assertThat(vectorSimilarity.isSimilar(
                doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(8.0, 7.0, 6.0),
                0.15).first).isTrue()
    }
}

