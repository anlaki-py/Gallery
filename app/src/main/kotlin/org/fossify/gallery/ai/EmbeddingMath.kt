package org.fossify.gallery.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Versioned alongside the pinned model and preprocessing, so incompatible vectors never mix. */
object EmbeddingMath {
    const val DIMENSION = 768
    const val INDEX_VERSION = "gemma2-86b903a2-v2-70t-256ctx-3frames"

    fun normalized(vector: FloatArray): FloatArray {
        require(vector.size in 32..8192 && vector.all { it.isFinite() }) { "Invalid embedding from model" }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm > 1e-12) { "Empty embedding from model" }
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    fun encode(vector: FloatArray): ByteArray {
        val normalized = normalized(vector)
        return ByteBuffer.allocate(normalized.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            normalized.forEach { putFloat(it) }
        }.array()
    }

    fun score(query: FloatArray, blob: ByteArray): Float? {
        if (query.size !in 32..8192 || blob.size != query.size * 4) return null
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0f
        for (value in query) sum += value * buffer.float
        return sum.takeIf { it.isFinite() }?.coerceIn(-1f, 1f)
    }

    /** MediaMetadataRetriever takes microseconds; avoid requesting the exclusive end timestamp. */
    fun videoTimesUs(durationMs: Long): List<Long> {
        require(durationMs > 0 && durationMs <= Long.MAX_VALUE / 1000)
        val endUs = durationMs * 1000 - 1
        return listOf(0L, endUs / 2, endUs).distinct()
    }
}
