package org.fossify.gallery.ai

import android.content.Context
import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiDiagnosticsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("ai_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun reportPersistsCodeMetadataAndBothDelegateFailures() {
        val gpu = IllegalArgumentException("GPU delegate unavailable")
        val cpu = IllegalStateException("CPU operator unsupported", UnsatisfiedLinkError("Missing symbol"))
        cpu.addSuppressed(gpu)
        val status = AiDiagnostics.record(context, AiErrorCode.LOAD_FALLBACK, cpu)
        val saved = AiDiagnostics.lastReport(context.createConfigurationContext(context.resources.configuration))
        assertTrue(status.contains("AI-202"))
        assertTrue(saved.contains("CPU operator unsupported"))
        assertTrue(saved.contains("GPU delegate unavailable"))
        assertTrue(saved.contains("Missing symbol"))
        assertTrue(saved.contains("Suppressed:"))
        assertTrue(saved.contains("Caused by:"))
        assertTrue(saved.contains("Runtime:"))
        assertTrue(saved.contains("Model bytes:"))
    }

    @Test
    fun surroundingWorkerDoesNotOverwriteNativeFailure() {
        val cause = IllegalStateException("CPU delegate unavailable")
        val status = AiDiagnostics.record(context, AiErrorCode.LOAD_CPU, cause)
        val saved = AiDiagnostics.lastReport(context)
        val wrapped = EmbeddingEngineException(status, cause, AiErrorCode.LOAD_CPU)
        assertEquals(status, AiDiagnostics.record(context, AiErrorCode.INDEX, wrapped))
        assertEquals(saved, AiDiagnostics.lastReport(context))
    }

    @Test
    fun inferenceAndSearchReportsExcludePrivateMessagesThroughoutExceptionChain() {
        val cause = IllegalStateException("my private search words")
        cause.addSuppressed(IllegalArgumentException("/storage/emulated/0/private-photo.jpg"))
        val error = RuntimeException("content://media/external/images/42", cause)
        AiDiagnostics.record(context, AiErrorCode.SEARCH, error)
        val report = AiDiagnostics.lastReport(context)
        assertFalse(report.contains("my private search words"))
        assertFalse(report.contains("private-photo"))
        assertFalse(report.contains("content://"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("Suppressed:"))
    }

    @Test
    fun modelLoadingMessagesRetainRuntimeReasonButRedactPathsAndUrls() {
        val report = AiDiagnostics.formatException(
            IllegalStateException("Unsupported operator in /data/user/0/app/model.litertlm from https://host/model?token=secret"), true
        )
        assertTrue(report.contains("Unsupported operator"))
        assertFalse(report.contains("/data/user"))
        assertFalse(report.contains("token=secret"))
    }

    @Test
    fun cyclicAndOversizedExceptionsProduceBoundedReport() {
        val first = IllegalStateException("x".repeat(100_000))
        val second = RuntimeException("second", first)
        first.initCause(second)
        repeat(20) { first.addSuppressed(IllegalStateException("y".repeat(100_000))) }
        val report = AiDiagnostics.formatException(first, true)
        assertTrue(report.isNotBlank())
        assertTrue(report.length <= 48_000)
    }

    @Test
    fun noFailureHasNoStaleOrInventedReport() {
        assertEquals("", AiDiagnostics.lastReport(context))
    }
}
