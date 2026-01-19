package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.gtx.extensions.vectordb.VectorSimilarity.isSimilar
import org.junit.jupiter.api.Test

class VectorSimilarityTest {

    @Test
    fun testSimilarity() {
        assertThat(isSimilar(
                doubleArrayOf(0.0, 1.0),
                doubleArrayOf(0.0, 1.0),
                0.0)).isEqualTo(SimilarityResult(true, 1.0))

        assertThat(isSimilar(
                doubleArrayOf(1.0, 0.0, 0.577350269),
                doubleArrayOf(0.577350269, 0.577350269, 0.577350269),
                0.0)).isEqualTo(SimilarityResult(false, 0.9106836021143723))

        assertThat(isSimilar(
                doubleArrayOf(1.0, 0.0, 0.577350269),
                doubleArrayOf(0.577350269, 0.577350269, 0.577350269),
                0.19)).isEqualTo(SimilarityResult(true, 0.9106836021143723))
    }
}
