package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class VectorTest {

    @Test
    fun testIsValidVectorFormat() {
        assertThat(isValidVectorFormat("[1, 2, 3]")).isTrue()
        assertThat(isValidVectorFormat("[-1, -2, -3]")).isTrue()
        assertThat(isValidVectorFormat("[1,     2, 3]")).isTrue()
        assertThat(isValidVectorFormat("[0.0001, 0.0002, 0.0003]")).isTrue()
        assertThat(isValidVectorFormat("[-2.7173894e-34, 2]")).isTrue()

        assertThat(isValidVectorFormat("[]")).isFalse()
        assertThat(isValidVectorFormat("1, 2, 3]")).isFalse()
        assertThat(isValidVectorFormat("[1 2, 3]")).isFalse()
        assertThat(isValidVectorFormat("[a, b, c]")).isFalse()
    }

    @Test
    fun testIsVectorExpectedDimensions() {
        assertThat(isVectorExpectedDimensions("[1, 2, 3]", 3)).isTrue()
        assertThat(isVectorExpectedDimensions("[1, 2]", 3)).isFalse()
    }
}