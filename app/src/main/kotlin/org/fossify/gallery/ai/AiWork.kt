package org.fossify.gallery.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.fossify.gallery.R
import org.fossify.gallery.activities.AiSearchSettingsActivity
import java.io.IOException
import java.util.concurrent.TimeUnit

object AiWork {
    const val DOWNLOAD = "ai-model-download"
    const val INDEX = "ai-media-index"
    const val IMPORT_TAG = "ai-model-zip-import"
    const val IMPORT_URI = "model_zip_uri"

    fun importZip(context: Context, uri: android.net.Uri): androidx.work.Operation {
        require(uri.scheme == "content") { "Choose a ZIP using the system file picker." }
        AiPreferences(context).downloadStatus = context.getString(R.string.ai_import_queued)
        val request = OneTimeWorkRequestBuilder<AiModelImportWorker>()
            .setInputData(workDataOf(IMPORT_URI to uri.toString()))
            .addTag(IMPORT_TAG)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        // Both import and download share this unique name; they cannot publish competing models.
        return WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.KEEP, request)
    }

    fun download(context: Context) {
        val prefs = AiPreferences(context)
        prefs.downloadStatus = context.getString(R.string.ai_download_queued)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true).build()
        val request = OneTimeWorkRequestBuilder<AiModelDownloadWorker>().setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.KEEP, request)
    }

    fun scan(context: Context, manual: Boolean = false) {
        val prefs = AiPreferences(context)
        if (!prefs.enabled || !AiModelStore.ready(context)) return
        if (manual) prefs.paused = false
        if (prefs.paused || !AiMediaReader.hasPermission(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(INDEX, ExistingWorkPolicy.KEEP, indexRequest())
    }

    private fun indexRequest() = OneTimeWorkRequestBuilder<AiIndexWorker>()
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()

    fun continueScan(context: Context) {
        val prefs = AiPreferences(context)
        if (prefs.enabled && !prefs.paused) {
            // A fresh child avoids increasing retry backoff for every normal checkpointed chunk.
            WorkManager.getInstance(context).enqueueUniqueWork(INDEX, ExistingWorkPolicy.APPEND_OR_REPLACE, indexRequest())
        }
    }

    fun pause(context: Context) {
        AiPreferences(context).paused = true
        WorkManager.getInstance(context).cancelUniqueWork(INDEX)
    }

    fun disable(context: Context) {
        AiPreferences(context).enabled = false
        WorkManager.getInstance(context).apply { cancelUniqueWork(INDEX); cancelUniqueWork(DOWNLOAD) }
    }

    fun foreground(context: Context, download: Boolean, text: String, title: Int? = null): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "ai_search_work"
        manager.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.ai_search_settings), NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(context, 4100, Intent(context, AiSearchSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(if (download) android.R.drawable.stat_sys_download else android.R.drawable.ic_menu_search)
            .setContentTitle(context.getString(title ?: if (download) R.string.ai_downloading else R.string.ai_indexing))
            .setContentText(text).setContentIntent(intent).setOnlyAlertOnce(true).setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(if (download) 4101 else 4102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(if (download) 4101 else 4102, notification)
    }
}

class AiModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo() = AiWork.foreground(applicationContext, true, applicationContext.getString(R.string.ai_downloading))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AiPreferences(applicationContext)
        if (!prefs.enabled) return@withContext Result.success()
        try {
            setForeground(getForegroundInfo())
            val coroutineContext = currentCoroutineContext()
            AiModelStore.download(applicationContext, { coroutineContext.ensureActive(); if (isStopped) throw CancellationException() }) { bytes, verifying ->
                val message = if (verifying) applicationContext.getString(R.string.ai_verifying)
                else applicationContext.getString(R.string.ai_download_progress, bytes * 100 / AiModelStore.SIZE)
                prefs.downloadStatus = message
                setForegroundAsync(AiWork.foreground(applicationContext, true, message))
            }
            prefs.downloadStatus = applicationContext.getString(R.string.ai_model_ready)
            AiWork.scan(applicationContext)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            prefs.downloadStatus = e.message ?: "Download failed. Check storage and network, then retry."
            val retryable = (e is ModelDownloadException && e.retryable) || (e is IOException && e !is ModelDownloadException)
            if (retryable && runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

class AiIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo() = AiWork.foreground(applicationContext, false, applicationContext.getString(R.string.ai_checking_media))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = AiPreferences(context)
        if (!prefs.enabled || prefs.paused || !AiModelStore.ready(context)) return@withContext Result.success()
        val db = AiIndexDatabase.get(context)
        try {
            setForeground(getForegroundInfo())
            prefs.indexStatus = context.getString(R.string.ai_checking_media)
            val coroutineContext = currentCoroutineContext()
            val checkCancelled = { coroutineContext.ensureActive(); if (isStopped || prefs.paused || !prefs.enabled) throw CancellationException() }
            val media = AiMediaReader.list(context, checkCancelled)
            checkCancelled()
            db.reconcile(media)
            val started = android.os.SystemClock.elapsedRealtime()
            var lastReport = 0L
            for (item in media) {
                checkCancelled()
                if (!db.needsIndexing(item)) continue
                // Short chunks let Android reschedule fairly; system-wide daily quotas still apply.
                if (android.os.SystemClock.elapsedRealtime() - started > 6 * 60 * 1000L) {
                    prefs.indexStatus = context.getString(R.string.ai_scan_continuing)
                    AiWork.continueScan(context)
                    return@withContext Result.success()
                }
                try {
                    if (context.noBackupFilesDir.usableSpace < 32L * 1024 * 1024) throw IOException("Storage is full. Free space, then resume scanning.")
                    val vectors = AiMediaReader.embedMedia(context, item, checkCancelled) { bytes ->
                        withContext(AiEmbeddingEngine.dispatcher) { AiEmbeddingEngine.image(context, bytes) }
                    }
                    checkCancelled()
                    if (AiMediaReader.unchanged(context, item)) db.save(item, vectors)
                } catch (e: MediaDecodeException) {
                    db.failed(item, e.message ?: "Unreadable media")
                } catch (e: SecurityException) {
                    throw IOException("Media access changed. Grant Gallery access again, then scan.", e)
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReport > 1500) {
                    val stats = db.stats()
                    val status = context.getString(R.string.ai_scan_progress, stats.indexed, media.size, stats.failed)
                    prefs.indexStatus = status
                    setForeground(AiWork.foreground(context, false, status))
                    lastReport = now
                }
                yield()
            }
            val stats = db.stats()
            prefs.indexStatus = context.getString(R.string.ai_scan_complete, stats.indexed, stats.failed)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: OutOfMemoryError) {
            prefs.indexStatus = "Not enough memory. Close other apps, enable CPU mode, then resume scanning."
            AiEmbeddingEngine.release()
            Result.failure()
        } catch (e: Exception) {
            prefs.indexStatus = e.message ?: "Scanning failed. Please try again."
            if (e is IOException && runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
