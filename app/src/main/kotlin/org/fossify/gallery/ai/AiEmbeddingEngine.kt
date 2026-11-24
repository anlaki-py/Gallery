/*
 * Adapted from Google AI Edge Gallery's UniversalOnDeviceEmbedder (Apache-2.0).
 * Copyright 2026 Google LLC. See AI_SEARCH.md and THIRD_PARTY_AI_NOTICES.md.
 */
package org.fossify.gallery.ai

import android.content.Context
import android.os.Build
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class EmbeddingEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** All native create/infer/close calls use one thread, including GPU fallback and idle cleanup. */
object AiEmbeddingEngine {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "gallery-ai") }
    val dispatcher = executor.asCoroutineDispatcher()
    private var engine: UniversalEmbedder? = null
    private var cpu = false
    private var configuredCpuOnly = false
    private var lastUsed = 0L
    private val elapsed get() = android.os.SystemClock.elapsedRealtime()

    init {
        executor.scheduleWithFixedDelay({
            if (engine != null && elapsed - lastUsed > 30_000) closeNative()
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun create(context: Context, useCpu: Boolean): UniversalEmbedder {
        val delegate = if (useCpu) Delegate.CPU else Delegate.GPU
        val builder = UniversalEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(AiModelStore.file(context).absolutePath).setDelegate(delegate).build())
            .setL2Normalize(true)
            .setMaxInputLength(256)
            .setVisionTokensPerImage(70)
            .setCacheDir(java.io.File(context.cacheDir, "ai_v1_${delegate.name}").apply { mkdirs() }.absolutePath)
            .setAudioDelegate(Delegate.CPU)
        if (useCpu) { builder.setTextDelegate(Delegate.CPU); builder.setVisionDelegate(Delegate.CPU) }
        return UniversalEmbedder.createFromOptions(context.applicationContext, builder.build())
    }

    private fun prepare(context: Context) {
        if (!android.os.Process.is64Bit()) throw EmbeddingEngineException("AI search requires a 64-bit Android installation.")
        check(AiModelStore.ready(context)) { "Download or import the AI model in Settings first." }
        val requestedCpu = AiPreferences(context).cpuOnly
        if (engine != null && configuredCpuOnly != requestedCpu) closeNative()
        if (engine != null) return
        configuredCpuOnly = requestedCpu
        // Edge Gallery avoids GPU on Mali/Exynos. MediaTek Dimensity phones also use Mali GPUs.
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val hardware = "${Build.HARDWARE} $manufacturer".lowercase()
        cpu = requestedCpu || hardware.contains("exynos") || hardware.contains("mediatek") || hardware.contains("mt6") || hardware.contains("mt8")
        try { engine = create(context, cpu) }
        catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            if (cpu) throw EmbeddingEngineException("AI model could not load. Free memory, or download the model again.", e)
            cpu = true
            try { engine = create(context, true) }
            catch (fallback: Throwable) { throw EmbeddingEngineException("AI model could not load on GPU or CPU. Free memory and try again.", fallback) }
        }
    }

    /** Caller must already run on [dispatcher]; indexing yields it after every frame for searches. */
    fun image(context: Context, jpeg: ByteArray): FloatArray = infer(context) { it.embedContent(listOf(jpeg as Any)).embeddings().firstOrNull()?.floatEmbedding() }

    suspend fun text(context: Context, text: String): FloatArray = withContext(dispatcher) {
        infer(context) { it.embedText(text.take(1000)).embeddings().firstOrNull()?.floatEmbedding() }
    }

    private fun infer(context: Context, operation: (UniversalEmbedder) -> FloatArray?): FloatArray {
        lastUsed = elapsed
        prepare(context)
        try {
            return EmbeddingMath.normalized(operation(checkNotNull(engine)) ?: error("Model returned no embedding"))
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            if (cpu || e is OutOfMemoryError) {
                closeNative()
                throw EmbeddingEngineException("AI inference failed. Free memory or enable CPU mode in AI settings, then retry.", e)
            }
            closeNative()
            cpu = true
            try {
                engine = create(context, true)
                return EmbeddingMath.normalized(operation(checkNotNull(engine)) ?: error("Model returned no embedding"))
            } catch (fallback: Throwable) {
                closeNative()
                throw EmbeddingEngineException("AI inference failed on GPU and CPU. Try again after freeing memory.", fallback)
            }
        } finally { lastUsed = elapsed }
    }

    suspend fun release() = withContext(dispatcher) { closeNative() }

    private fun closeNative() {
        try { engine?.close() } catch (_: Exception) { }
        engine = null
    }
}
