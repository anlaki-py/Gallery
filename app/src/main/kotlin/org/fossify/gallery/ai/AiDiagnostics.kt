package org.fossify.gallery.ai

import android.content.Context
import android.os.Build
import org.fossify.gallery.BuildConfig
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/** Stable codes distinguish model startup, inference and the surrounding AI operations. */
enum class AiErrorCode(val id: String, val summary: String) {
    UNSUPPORTED_PROCESS("AI-101", "AI requires a 64-bit Android process."),
    MODEL_MISSING("AI-102", "The verified AI model is missing."),
    LOAD_CPU("AI-201", "The AI model could not load on CPU."),
    LOAD_FALLBACK("AI-202", "The AI model could not load on GPU or CPU."),
    INFERENCE("AI-301", "AI inference failed."),
    INFERENCE_FALLBACK("AI-302", "AI inference failed on GPU and CPU."),
    DOWNLOAD("AI-401", "The model download failed."),
    IMPORT("AI-402", "The model import failed."),
    INDEX("AI-501", "AI scanning failed."),
    SEARCH("AI-502", "AI search failed."),
    MEDIA("AI-503", "A media item could not be indexed."),
    MAINTENANCE("AI-504", "AI index or model maintenance failed.")
}

/** Saves the last failure locally. Reports contain no media paths, query text or media bytes. */
object AiDiagnostics {
    private const val LIMIT = 48_000
    private const val KEY = "last_error_report"

    /** Returns the saved report, including failures from a previous app process. */
    fun lastReport(context: Context): String = preferences(context).getString(KEY, "").orEmpty()

    /** Records an error and returns a short status that points to the copy action in AI settings. */
    fun record(context: Context, code: AiErrorCode, error: Throwable): String {
        // The native engine already saved both delegate failures. Keep that more useful report.
        if (error is EmbeddingEngineException && error.code != null) return error.message.orEmpty()
        val details = buildString {
            appendLine("Gallery AI error report v1")
            appendLine("Code: ${code.id}")
            appendLine("Summary: ${code.summary}")
            appendLine("Time: ${Instant.now()}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("64-bit process: ${android.os.Process.is64Bit()}")
            appendLine("CPU-only setting: ${AiPreferences(context).cpuOnly}")
            appendLine("Runtime: tasks-retrieval 1.2.0; LiteRT-LM 0.18.1")
            appendLine("Model SHA-256 expected: ${AiModelStore.SHA256}")
            try {
                appendLine("Model bytes: ${AiModelStore.file(context).length()} / ${AiModelStore.SIZE}")
                appendLine("Model verified marker: ${AiModelStore.ready(context)}")
                appendLine("Free model storage bytes: ${context.noBackupFilesDir.usableSpace}")
            } catch (_: Exception) { appendLine("Model storage information unavailable") }
            appendLine("Exception details:")
            // Only initialization runs before any private input reaches the runtime.
            append(formatException(error, code == AiErrorCode.LOAD_CPU || code == AiErrorCode.LOAD_FALLBACK))
        }.take(LIMIT)
        preferences(context).edit().putString(KEY, details).apply()
        val summary = if (error is ModelDownloadException || error is ModelImportException) error.message ?: code.summary else code.summary
        return "[${code.id}] $summary Open AI settings and tap Copy AI error report."
    }

    internal fun formatException(error: Throwable, includeMessages: Boolean): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun write(value: Throwable, label: String, depth: Int) {
            if (length >= LIMIT || depth > 16 || !seen.add(value)) return
            appendLine("$label${value.javaClass.name}")
            if (includeMessages || value is ModelDownloadException || value is ModelImportException) {
                val message = value.message.orEmpty().take(8_000)
                    .replace(Regex("(?:content|file|https?)://\\S+"), "[URI]")
                    .replace(Regex("/[^\\s\"']+"), "[path]")
                if (message.isNotBlank()) appendLine(message)
            } else appendLine("Exception message omitted to protect media names and search text.")
            value.stackTrace.take(80).forEach { appendLine("  at $it") }
            value.suppressed.take(8).forEach { write(it, "Suppressed: ", depth + 1) }
            value.cause?.let { write(it, "Caused by: ", depth + 1) }
        }
        write(error, "", 0)
    }.take(LIMIT)

    private fun preferences(context: Context) = context.getSharedPreferences("ai_diagnostics", Context.MODE_PRIVATE)
}
