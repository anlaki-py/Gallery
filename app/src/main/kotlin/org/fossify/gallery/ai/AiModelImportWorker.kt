package org.fossify.gallery.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.fossify.gallery.R
import java.io.File

class AiModelImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo() = foreground(applicationContext.getString(R.string.ai_importing))

    private fun foreground(message: String) = AiWork.foreground(applicationContext, true, message, R.string.ai_importing)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = AiPreferences(context)
        if (!prefs.enabled) return@withContext Result.success()
        val selected = inputData.getString(AiWork.IMPORT_URI)
        if (selected.isNullOrBlank()) {
            prefs.downloadStatus = context.getString(R.string.ai_import_select_again)
            return@withContext Result.failure()
        }
        var archive: File? = null
        try {
            setForeground(getForegroundInfo())
            val coroutineContext = currentCoroutineContext()
            val checkCancelled = {
                coroutineContext.ensureActive()
                if (isStopped || !prefs.enabled) throw CancellationException()
            }
            val uri = Uri.parse(selected)
            if (uri.scheme != "content") throw ModelImportException(context.getString(R.string.ai_import_select_again))
            val folder = File(context.cacheDir, "ai-model-import").apply {
                if (!isDirectory && !mkdirs()) throw ModelImportException("Cannot create an import folder. Free some storage and retry.")
            }
            val copy = File(folder, "$id.zip")
            archive = copy
            // Only one model operation can be active; reclaim abandoned ZIP copies after a process death.
            folder.listFiles()?.filter { it != copy && it.extension == "zip" }?.forEach { it.delete() }
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { bytes -> bytes > 0 } else null
            }
            var lastReport = 0L
            fun report(message: String, force: Boolean = false) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (force || now - lastReport >= 1000) {
                    prefs.downloadStatus = message
                    setForegroundAsync(foreground(message))
                    lastReport = now
                }
            }
            report(context.getString(R.string.ai_import_copying), true)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ModelImportException(context.getString(R.string.ai_import_select_again))
            input.use {
                AiModelZipImporter.copyArchive(it, copy, size, checkCancelled = checkCancelled) { bytes ->
                    report(context.getString(R.string.ai_import_copy_progress, bytes / (1024 * 1024)))
                }
            }
            checkCancelled()
            report(context.getString(R.string.ai_import_verifying), true)
            AiEmbeddingEngine.release()
            AiModelStore.importZip(context, copy, checkCancelled) { bytes ->
                report(context.getString(R.string.ai_import_progress, bytes * 100 / AiModelStore.SIZE))
            }
            prefs.downloadStatus = context.getString(R.string.ai_import_complete)
            AiWork.scan(context)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AiDiagnostics.record(context, AiErrorCode.IMPORT, e)
            prefs.downloadStatus = context.getString(R.string.ai_import_select_again)
            Result.failure()
        } catch (e: Exception) {
            if (isStopped || !prefs.enabled) throw CancellationException()
            prefs.downloadStatus = AiDiagnostics.record(context, AiErrorCode.IMPORT, e)
            Result.failure()
        } finally {
            archive?.delete()
        }
    }
}
