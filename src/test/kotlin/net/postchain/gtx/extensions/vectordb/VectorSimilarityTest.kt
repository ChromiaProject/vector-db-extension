package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.postchain.gtx.extensions.vectordb.VectorSimilarity.isSimilar
import org.junit.jupiter.api.Test

class VectorSimilarityTest {

    @Test
    fun testSimilarity() {
        assertThat(isSimilar(
                doubleArrayOf(0.0, 1.0),
                        doubleArrayOf(0.0, 1.0),
                0.0).first).isTrue()
        assertThat(isSimilar(
                doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(4.0, 5.0, 6.0),
                0.0).first).isFalse()
        assertThat(isSimilar(
                doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(8.0, 7.0, 6.0),
                0.0).first).isFalse()
        assertThat(isSimilar(
                doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(8.0, 7.0, 6.0),
                0.15).first).isTrue()
    }
}

