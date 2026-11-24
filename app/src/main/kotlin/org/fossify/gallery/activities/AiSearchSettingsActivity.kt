package org.fossify.gallery.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.ai.*
import org.fossify.gallery.databinding.ActivityAiSearchSettingsBinding

class AiSearchSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityAiSearchSettingsBinding::inflate)
    private val prefs by lazy { AiPreferences(this) }
    private lateinit var enabled: MaterialSwitch
    private lateinit var wifi: MaterialSwitch
    private lateinit var cpu: MaterialSwitch
    private lateinit var modelStatus: TextView
    private lateinit var indexStatus: TextView
    private lateinit var counts: TextView
    private lateinit var download: MaterialButton
    private lateinit var importModel: MaterialButton
    private lateinit var scan: MaterialButton
    private lateinit var pause: MaterialButton
    private lateinit var retry: MaterialButton
    private lateinit var errorReport: MaterialButton
    private lateinit var clear: MaterialButton
    private var downloadState: WorkInfo.State? = null
    private var importing = false
    private var indexState: WorkInfo.State? = null
    private var stats = AiIndexStats(0, 0, 0)
    private var maintenance = false
    private val pickModelZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSelectedZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.aiAppbar), padBottomSystem = listOf(binding.aiScroll))
        setupMaterialScrollListener(binding.aiScroll, binding.aiAppbar)
        paragraph(R.string.ai_explanation)
        enabled = switch(R.string.ai_enable, prefs.enabled) { value ->
            prefs.enabled = value
            if (value) { prefs.paused = false; AiWork.scan(this); requestNotifications() }
            else { AiWork.disable(this); lifecycleScope.launch { AiEmbeddingEngine.release() } }
            render()
        }
        if (!android.os.Process.is64Bit()) {
            enabled.isEnabled = false
            paragraph(R.string.ai_requires_64bit)
        }
        paragraph(R.string.ai_model_description)
        wifi = switch(R.string.ai_wifi_only, prefs.wifiOnly) { value ->
            prefs.wifiOnly = value
            if (downloadState?.isFinished == false && !importing) lifecycleScope.launch {
                withContext(Dispatchers.IO) { WorkManager.getInstance(this@AiSearchSettingsActivity).cancelUniqueWork(AiWork.DOWNLOAD).result.get() }
                prefs.downloadStatus = getString(R.string.ai_download_paused)
                render()
            }
        }
        cpu = switch(R.string.ai_cpu_only, prefs.cpuOnly) { value ->
            prefs.cpuOnly = value
            lifecycleScope.launch { AiEmbeddingEngine.release() }
        }
        paragraph(R.string.ai_cpu_description)
        modelStatus = paragraph(R.string.ai_model_missing)
        download = button(R.string.ai_download) {
            if (downloadState?.isFinished == false) {
                WorkManager.getInstance(this).cancelUniqueWork(AiWork.DOWNLOAD)
                prefs.downloadStatus = getString(if (importing) R.string.ai_import_cancelled else R.string.ai_download_paused)
            } else if (AiModelStore.ready(this)) {
                redownloadModel()
            } else AiWork.download(this)
            render()
        }
        paragraph(R.string.ai_import_description)
        importModel = button(R.string.ai_import_zip) {
            // Some file providers mislabel ZIP MIME types. Validate the file contents after selection.
            pickModelZip.launch(arrayOf("*/*"))
        }
        paragraph(R.string.ai_scan_explanation)
        counts = paragraph(R.string.ai_scan_idle)
        indexStatus = paragraph(R.string.ai_scan_idle)
        scan = button(R.string.ai_scan) {
            requestMediaPermissions {
                requestNotifications()
                AiWork.scan(this, manual = true)
                render()
            }
        }
        pause = button(R.string.ai_scan_pause) { AiWork.pause(this); render() }
        retry = button(R.string.ai_retry_failed) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { AiIndexDatabase.get(applicationContext).retryFailures() }
                    AiWork.scan(this@AiSearchSettingsActivity, manual = true)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    prefs.indexStatus = AiDiagnostics.record(this@AiSearchSettingsActivity, AiErrorCode.MAINTENANCE, e)
                    render()
                }
            }
        }
        clear = button(R.string.ai_clear_index) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.ai_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> clearIndex() }.show()
        }
        button(R.string.ai_manage_permissions) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        paragraph(R.string.ai_error_report_description)
        errorReport = button(R.string.ai_copy_error_report) {
            val report = AiDiagnostics.lastReport(this)
            if (report.isNotBlank()) {
                val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.ai_error_report_title)
                    .setMessage(report).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.ai_copy_error_report) { _, _ ->
                        getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.ai_error_report_title), report))
                        toast(R.string.ai_error_report_copied)
                    }.show()
                dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            }
        }
        paragraph(R.string.ai_background_note)
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(AiWork.DOWNLOAD).observe(this) {
            val active = it.firstOrNull { work -> !work.state.isFinished }
            downloadState = (active ?: it.firstOrNull())?.state
            importing = active?.tags?.contains(AiWork.IMPORT_TAG) == true
            render()
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(AiWork.INDEX).observe(this) {
            indexState = (it.firstOrNull { work -> !work.state.isFinished } ?: it.firstOrNull())?.state
            render()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try {
                        stats = withContext(Dispatchers.IO) { AiIndexDatabase.get(applicationContext).stats() }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { prefs.indexStatus = AiDiagnostics.record(this@AiSearchSettingsActivity, AiErrorCode.MAINTENANCE, e) }
                    render()
                    delay(1500)
                }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.aiAppbar, NavigationIcon.Arrow)
    }

    private fun importSelectedZip(uri: android.net.Uri) {
        if (!prefs.enabled || maintenance) return
        maintenance = true
        render()
        lifecycleScope.launch {
            try {
                val active = withContext(Dispatchers.IO) {
                    WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(AiWork.DOWNLOAD).get()
                        .any { !it.state.isFinished }
                }
                if (active) {
                    toast(R.string.ai_import_busy)
                    return@launch
                }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val previous = prefs.lastImportUri
                if (previous.isNotBlank() && previous != uri.toString()) {
                    try { contentResolver.releasePersistableUriPermission(previous.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { }
                }
                prefs.lastImportUri = uri.toString()
                requestNotifications()
                withContext(Dispatchers.IO) { AiWork.importZip(applicationContext, uri).result.get() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: SecurityException) {
                AiDiagnostics.record(this@AiSearchSettingsActivity, AiErrorCode.IMPORT, e)
                prefs.downloadStatus = getString(R.string.ai_import_select_again)
                toast(R.string.ai_import_select_again)
            } catch (e: Exception) {
                prefs.downloadStatus = AiDiagnostics.record(this@AiSearchSettingsActivity, AiErrorCode.IMPORT, e)
                toast(R.string.ai_import_failed)
            } finally { maintenance = false; render() }
        }
    }

    private fun clearIndex() {
        maintenance = true
        AiWork.pause(this)
        render()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).cancelUniqueWork(AiWork.INDEX).result.get() }
                // Barrier on the native thread waits for any in-flight frame, before clearing DB.
                AiEmbeddingEngine.release()
                withContext(Dispatchers.IO) { AiIndexDatabase.get(applicationContext).clear() }
                prefs.indexStatus = getString(R.string.ai_index_cleared)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                prefs.indexStatus = AiDiagnostics.record(this@AiSearchSettingsActivity, AiErrorCode.MAINTENANCE, e)
            } finally { maintenance = false; render() }
        }
    }

    private fun redownloadModel() {
        maintenance = true
        val wasPaused = prefs.paused
        AiWork.pause(this)
        render()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).cancelUniqueWork(AiWork.INDEX).result.get() }
                AiEmbeddingEngine.release()
                withContext(Dispatchers.IO) { AiModelStore.remove(applicationContext) }
                prefs.paused = wasPaused
                AiWork.download(this@AiSearchSettingsActivity)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                prefs.downloadStatus = AiDiagnostics.record(this@AiSearchSettingsActivity, AiErrorCode.MAINTENANCE, e)
            } finally { maintenance = false; render() }
        }
    }

    private fun render() {
        if (!::download.isInitialized || !::clear.isInitialized) return
        if (::errorReport.isInitialized) errorReport.isEnabled = AiDiagnostics.lastReport(this).isNotBlank()
        val ready = AiModelStore.ready(this)
        val downloading = downloadState?.isFinished == false
        val scanning = indexState?.isFinished == false
        wifi.isEnabled = prefs.enabled && !maintenance && !importing
        cpu.isEnabled = prefs.enabled && !maintenance
        download.isEnabled = prefs.enabled && !maintenance
        download.setText(if (importing) R.string.ai_import_cancel else if (downloading) R.string.ai_download_pause else if (ready) R.string.ai_download_again else R.string.ai_download_resume)
        importModel.isEnabled = prefs.enabled && !maintenance && !downloading && !scanning
        modelStatus.text = when {
            downloadState == WorkInfo.State.ENQUEUED || downloadState == WorkInfo.State.BLOCKED -> getString(if (importing) R.string.ai_import_queued else R.string.ai_download_queued)
            prefs.downloadStatus.isNotEmpty() -> prefs.downloadStatus
            ready -> getString(R.string.ai_model_ready)
            else -> getString(R.string.ai_model_missing)
        }
        counts.text = getString(R.string.ai_index_counts, stats.indexed, stats.pending, stats.failed)
        scan.isEnabled = prefs.enabled && ready && !maintenance && !downloading && (!scanning || prefs.paused)
        scan.setText(if (prefs.paused) R.string.ai_scan_resume else R.string.ai_scan)
        pause.isEnabled = scanning && !prefs.paused && !maintenance
        retry.isEnabled = prefs.enabled && ready && stats.failed > 0 && !scanning && !maintenance
        clear.isEnabled = !maintenance
        indexStatus.text = when {
            !prefs.enabled -> getString(R.string.ai_settings_disabled)
            prefs.paused -> if (prefs.indexStatus == getString(R.string.ai_index_cleared)) prefs.indexStatus else getString(R.string.ai_scan_paused)
            indexState == WorkInfo.State.ENQUEUED || indexState == WorkInfo.State.BLOCKED -> getString(R.string.ai_scan_waiting)
            prefs.indexStatus.isNotEmpty() -> prefs.indexStatus
            else -> getString(R.string.ai_scan_idle)
        }
    }

    private fun paragraph(resource: Int): TextView = TextView(this).apply {
        setText(resource); textSize = 14f; setTextColor(getProperTextColor())
        setPadding(0, dp(8), 0, dp(8))
        binding.aiHolder.addView(this)
    }

    private fun switch(resource: Int, checked: Boolean, changed: (Boolean) -> Unit): MaterialSwitch = MaterialSwitch(this).apply {
        setText(resource); textSize = 16f; setTextColor(getProperTextColor())
        isChecked = checked; minHeight = dp(56)
        binding.aiHolder.addView(this, LinearLayout.LayoutParams(-1, -2))
        setOnCheckedChangeListener { _, value -> changed(value) }
    }

    private fun button(resource: Int, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        setText(resource); isAllCaps = false; minHeight = dp(48)
        binding.aiHolder.addView(this, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4100)
        }
    }
}
