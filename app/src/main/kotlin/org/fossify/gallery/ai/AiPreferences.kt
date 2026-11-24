package org.fossify.gallery.ai

import android.content.Context
import androidx.core.content.edit

class AiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ai_search", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit { putBoolean("enabled", value) }
    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit { putBoolean("paused", value) }
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) = prefs.edit { putBoolean("wifi_only", value) }
    var cpuOnly: Boolean
        get() = prefs.getBoolean("cpu_only", false)
        set(value) = prefs.edit { putBoolean("cpu_only", value) }
    var downloadStatus: String
        get() = prefs.getString("download_status", "") ?: ""
        set(value) = prefs.edit { putString("download_status", value) }
    var indexStatus: String
        get() = prefs.getString("index_status", "") ?: ""
        set(value) = prefs.edit { putString("index_status", value) }
    var lastImportUri: String
        get() = prefs.getString("import_uri", "") ?: ""
        set(value) = prefs.edit { putString("import_uri", value) }
}
