package org.fossify.gallery.ai

import org.junit.Assert.*
import org.junit.Test

class EmbeddingMathTest {
    private fun vector(index: Int, value: Float = 1f) = FloatArray(EmbeddingMath.DIMENSION).also { it[index] = value }

    @Test fun normalizedBlobProducesCosineScores() {
        assertEquals(1f, EmbeddingMath.score(vector(0), EmbeddingMath.encode(vector(0, 7f)))!!, 1e-6f)
        assertEquals(0f, EmbeddingMath.score(vector(0), EmbeddingMath.encode(vector(1)))!!, 1e-6f)
        assertEquals(-1f, EmbeddingMath.score(vector(0), EmbeddingMath.encode(vector(0, -3f)))!!, 1e-6f)
    }

    @Test fun invalidVectorsAndCorruptRecordsAreRejected() {
        for (bad in listOf(FloatArray(0), FloatArray(768), vector(0, Float.NaN), vector(0, Float.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { EmbeddingMath.normalized(bad) }
        }
        assertNull(EmbeddingMath.score(vector(0), byteArrayOf(1, 2, 3)))
    }

    @Test fun videoTimestampsIncludeBeginningMiddleAndLastValidMicrosecond() {
        assertEquals(listOf(0L, 4_999_999L, 9_999_999L), EmbeddingMath.videoTimesUs(10_000))
        assertEquals(listOf(0L, 499L, 999L), EmbeddingMath.videoTimesUs(1))
        assertThrows(IllegalArgumentException::class.java) { EmbeddingMath.videoTimesUs(0) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingMath.videoTimesUs(Long.MAX_VALUE) }
    }

    @Test fun dimensionComesFromTheModelAndMismatchedRecordsAreSkipped() {
        val alternate = FloatArray(1024).also { it[5] = 1f }
        assertEquals(1f, EmbeddingMath.score(alternate, EmbeddingMath.encode(alternate))!!, 1e-6f)
        assertNull(EmbeddingMath.score(vector(0), EmbeddingMath.encode(alternate)))
    }
}
