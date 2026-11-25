package com.turbodm.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PrefsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("turbodm_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppPrefs> = _state.asStateFlow()

    private fun load(): AppPrefs = AppPrefs(
        defaultConnections = prefs.getInt("default_connections", 16).coerceIn(1, 32),
        maxConcurrent = prefs.getInt("max_concurrent", 3).coerceIn(1, 8),
        dynamicSegmentation = prefs.getBoolean("dynamic_segmentation", true),
        autoSortByCategory = prefs.getBoolean("auto_sort_by_category", true),
        defaultVideoQuality = prefs.getString("default_video_quality", "1080") ?: "1080",
        audioFormat = prefs.getString("audio_format", "mp3") ?: "mp3",
        wifiOnly = prefs.getBoolean("wifi_only", false),
        ytdlpVersion = prefs.getString("ytdlp_version", "Bundled") ?: "Bundled",
    )

    fun update(transform: (AppPrefs) -> AppPrefs) {
        val next = transform(_state.value)
        prefs.edit()
            .putInt("default_connections", next.defaultConnections.coerceIn(1, 32))
            .putInt("max_concurrent", next.maxConcurrent.coerceIn(1, 8))
            .putBoolean("dynamic_segmentation", next.dynamicSegmentation)
            .putBoolean("auto_sort_by_category", next.autoSortByCategory)
            .putString("default_video_quality", next.defaultVideoQuality)
            .putString("audio_format", next.audioFormat)
            .putBoolean("wifi_only", next.wifiOnly)
            .putString("ytdlp_version", next.ytdlpVersion)
            .apply()
        _state.value = next
    }
}
