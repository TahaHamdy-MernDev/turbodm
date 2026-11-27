package com.turbodm.android.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.turbodm.android.data.AppDatabase
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.data.MediaFormatOption
import com.turbodm.android.data.PlaylistVideoEntry
import com.turbodm.android.data.PrefsRepository
import com.turbodm.android.data.ProbeResult
import com.turbodm.android.service.DownloadForegroundService
import com.turbodm.android.util.Formatters
import com.turbodm.android.util.StoragePublisher
import com.turbodm.android.util.YouTubeCookieHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central coordinator managing HTTP multi-part downloads, yt-dlp social/playlist downloads,
 * Room SQLite persistence, MediaStore indexing, and Foreground Service notifications.
 */
class DownloadCoordinator(private val appContext: Context) {

    val db = AppDatabase.getInstance(appContext)
    val dao = db.downloadDao()
    val prefs = PrefsRepository(appContext)
    val httpEngine = SegmentedHttpEngine()
    val mediaEngine = YtDlpMediaEngine()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slotMutex = Mutex()
    private val activeSlotCount = MutableStateFlow(0)

    private class ActiveControl(
        val pauseFlag: AtomicBoolean,
        val cancelFlag: AtomicBoolean,
        @Volatile var isMedia: Boolean = false,
    ) {
        lateinit var job: Job
    }

    private val activeTasks = ConcurrentHashMap<String, ActiveControl>()

    private val _activeSummary = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val activeSummary: StateFlow<List<DownloadEntity>> = _activeSummary.asStateFlow()

    private val speedBuffers = ConcurrentHashMap<String, ArrayDeque<Double>>()
    private val _speedHistory = MutableStateFlow<Map<String, List<Double>>>(emptyMap())
    val speedHistory: StateFlow<Map<String, List<Double>>> = _speedHistory.asStateFlow()

    val downloadsFlow: Flow<List<DownloadEntity>> = dao.observeAll()

    init {
        scope.launch {
            dao.recoverInterruptedAsPaused()
            val hasCookies = YouTubeCookieHelper.hasSavedCookies(appContext)
            if (prefs.state.value.youtubeSignedIn != hasCookies) {
                prefs.update { it.copy(youtubeSignedIn = hasCookies) }
            }
            mediaEngine.ensureInitialized(appContext).onSuccess { ver ->
                prefs.update { it.copy(ytdlpVersion = ver) }
                val staleAgeMs = System.currentTimeMillis() - prefs.state.value.lastYtdlpUpdateMs
                val needsAutoUpdate = ver.startsWith("2024.") ||
                    ver.startsWith("2025.") ||
                    ver.equals("Bundled", ignoreCase = true) ||
                    ver.equals("Ready", ignoreCase = true) ||
                    staleAgeMs > 24L * 60L * 60L * 1000L
                if (needsAutoUpdate) {
                    updateYtDlp()
                }
            }
        }
    }

    fun getCategoryDir(category: String): File {
        val autoSort = prefs.state.value.autoSortByCategory
        val sub = if (!autoSort || category == "General" || category.isBlank()) "" else category
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(publicDownloads, "TurboDM")
        val target = if (sub.isEmpty()) base else File(base, sub)
        return if (target.mkdirs() || target.exists()) {
            target
        } else {
            val appBase = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val fallback = if (sub.isEmpty()) File(appBase, "TurboDM") else File(appBase, sub)
            fallback.apply { mkdirs() }
        }
    }

    private suspend fun <T> withConcurrencySlot(block: suspend () -> T): T {
        while (true) {
            var acquired = false
            slotMutex.withLock {
                val limit = prefs.state.value.maxConcurrent.coerceIn(1, 8)
                if (activeSlotCount.value < limit) {
                    activeSlotCount.value = activeSlotCount.value + 1
                    acquired = true
                }
            }
            if (acquired) break
            combine(activeSlotCount, prefs.state) { count, p ->
                count < p.maxConcurrent.coerceIn(1, 8)
            }.first { it }
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                slotMutex.withLock {
                    activeSlotCount.value = (activeSlotCount.value - 1).coerceAtLeast(0)
                }
            }
        }
    }

    suspend fun probeUrl(url: String): ProbeResult {
        val trimmed = url.trim()
        return if (Formatters.isMediaOrSocialUrl(trimmed)) {
            mediaEngine.probeMedia(appContext, trimmed, forcePlaylist = false)
        } else {
            httpEngine.probeDirectFile(trimmed)
        }
    }

    suspend fun probePlaylistExplicit(url: String): ProbeResult =
        mediaEngine.probeMedia(appContext, url.trim(), forcePlaylist = true)

    fun enqueueDirectDownload(
        probe: ProbeResult.DirectFile,
        customFilename: String = "",
        connections: Int = prefs.state.value.defaultConnections,
    ) {
        val finalName = Formatters.sanitizeFilename(customFilename.ifBlank { probe.filename })
        val category = Formatters.detectCategory(finalName)
        val saveDir = getCategoryDir(category).absolutePath
        val entity = DownloadEntity(
            url = probe.url,
            finalUrl = probe.finalUrl,
            filename = finalName,
            saveDir = saveDir,
            category = category,
            status = DownloadStatus.QUEUED.name,
            totalSize = probe.totalSize,
            resumable = probe.resumable,
            maxConnections = connections.coerceIn(1, 32),
            etag = probe.etag,
            isMedia = false,
            platform = "Direct File",
        )
        scope.launch {
            dao.upsert(entity)
            startDownload(entity.id)
        }
    }

    fun enqueueSingleMedia(
        media: ProbeResult.SingleMedia,
        format: MediaFormatOption,
        connections: Int = prefs.state.value.defaultConnections,
    ) {
        val cleanStem = Formatters.sanitizeFilename(media.title, "video")
        val filename = "$cleanStem.${format.ext}"
        val category = if (format.audioOnly) "Music" else "Video"
        val saveDir = getCategoryDir(category).absolutePath
        val entity = DownloadEntity(
            url = media.url,
            finalUrl = media.url,
            filename = filename,
            saveDir = saveDir,
            category = category,
            status = DownloadStatus.QUEUED.name,
            resumable = true,
            maxConnections = connections.coerceIn(1, 16),
            isMedia = true,
            mediaFormatId = format.id,
            mediaAudioOnly = format.audioOnly,
            thumbnailUrl = media.thumbnailUrl,
            platform = media.platform,
        )
        scope.launch {
            dao.upsert(entity)
            startDownload(entity.id)
        }
    }

    fun enqueuePlaylistEntries(
        playlistTitle: String,
        entries: List<PlaylistVideoEntry>,
        format: MediaFormatOption,
        connections: Int = prefs.state.value.defaultConnections,
    ) {
        val category = if (format.audioOnly) "Music" else "Video"
        val safeFolder = Formatters.sanitizeFilename(playlistTitle, "Playlist")
        val saveDir = File(getCategoryDir(category), safeFolder).apply { mkdirs() }.absolutePath

        scope.launch {
            entries.forEachIndexed { idx, entry ->
                val prefix = String.format("%02d - ", idx + 1)
                val filename = "$prefix${Formatters.sanitizeFilename(entry.title, "video_${idx + 1}")}.${format.ext}"
                val entity = DownloadEntity(
                    url = entry.url,
                    finalUrl = entry.url,
                    filename = filename,
                    saveDir = saveDir,
                    category = category,
                    status = DownloadStatus.QUEUED.name,
                    resumable = true,
                    maxConnections = connections.coerceIn(1, 16),
                    isMedia = true,
                    mediaFormatId = format.id,
                    mediaAudioOnly = format.audioOnly,
                    thumbnailUrl = entry.thumbnailUrl,
                    platform = "YouTube Playlist",
                )
                dao.upsert(entity)
                startDownload(entity.id)
            }
        }
    }

    fun startDownload(downloadId: String) {
        if (activeTasks.containsKey(downloadId)) return

        val pauseFlag = AtomicBoolean(false)
        val cancelFlag = AtomicBoolean(false)
        val ctrl = ActiveControl(pauseFlag, cancelFlag)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val entity = dao.getById(downloadId)
            if (entity == null) {
                activeTasks.remove(downloadId)
                return@launch
            }
            ctrl.isMedia = entity.isMedia
            DownloadForegroundService.start(appContext)
            try {
                withConcurrencySlot {
                    val fresh = dao.getById(downloadId) ?: return@withConcurrencySlot
                    ctrl.isMedia = fresh.isMedia
                    if (pauseFlag.get() || cancelFlag.get()) return@withConcurrencySlot

                    var lastDbWrite = 0L
                    val onProgress: suspend (DownloadEntity) -> Unit = { updated ->
                        val now = System.currentTimeMillis()
                        val isTerminal = updated.statusEnum() !in setOf(
                            DownloadStatus.DOWNLOADING,
                            DownloadStatus.PROBING,
                            DownloadStatus.MERGING
                        )
                        if (isTerminal || now - lastDbWrite >= 400L) {
                            lastDbWrite = now
                            dao.upsert(updated)
                        }
                        refreshActiveSnapshot(updated)
                    }

                    val result = if (fresh.isMedia) {
                        mediaEngine.downloadMedia(
                            context = appContext,
                            entity = fresh,
                            pauseRequested = pauseFlag,
                            cancelRequested = cancelFlag,
                            autoSortByCategory = prefs.state.value.autoSortByCategory,
                            onProgress = onProgress,
                        )
                    } else {
                        httpEngine.download(
                            entity = fresh,
                            pauseRequested = pauseFlag,
                            cancelRequested = cancelFlag,
                            dynamicSplitEnabled = prefs.state.value.dynamicSegmentation,
                            onProgress = onProgress,
                        )
                    }
                    dao.upsert(result)

                    if (result.statusEnum() == DownloadStatus.COMPLETED && result.filePath.isNotBlank()) {
                        if (!result.filePath.startsWith("content://")) {
                            scanMediaFile(result.filePath)
                        }
                        DownloadForegroundService.notifyCompleted(appContext, result)
                    }
                }
            } finally {
                activeTasks.remove(downloadId)
                refreshActiveSnapshot(null)
                if (activeTasks.isEmpty()) {
                    DownloadForegroundService.stop(appContext)
                }
            }
        }

        ctrl.job = job
        if (activeTasks.putIfAbsent(downloadId, ctrl) != null) {
            job.cancel()
            return
        }
        job.start()
    }

    fun resumeAll() {
        scope.launch {
            dao.getAll()
                .filter { it.statusEnum() in setOf(DownloadStatus.PAUSED, DownloadStatus.QUEUED) }
                .forEach { startDownload(it.id) }
        }
    }

    fun pauseDownload(downloadId: String) {
        val ctrl = activeTasks[downloadId]
        if (ctrl != null) {
            ctrl.pauseFlag.set(true)
            if (ctrl.isMedia) mediaEngine.destroyProcess(downloadId)
        } else {
            scope.launch {
                val item = dao.getById(downloadId) ?: return@launch
                if (item.statusEnum() == DownloadStatus.QUEUED) {
                    dao.upsert(item.copy(status = DownloadStatus.PAUSED.name, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    fun pauseAll() {
        activeTasks.keys.forEach { pauseDownload(it) }
    }

    fun deleteDownload(downloadId: String, deleteFile: Boolean) {
        val ctrl = activeTasks[downloadId]
        if (ctrl != null) {
            ctrl.cancelFlag.set(true)
            if (ctrl.isMedia) mediaEngine.destroyProcess(downloadId)
            ctrl.job.cancel()
        }
        speedBuffers.remove(downloadId)
        _speedHistory.value = _speedHistory.value - downloadId
        scope.launch {
            val item = dao.getById(downloadId)
            runCatching { File(appContext.filesDir, "ytdlp_work/$downloadId").deleteRecursively() }
            if (item != null) {
                runCatching { File(item.saveDir, "${item.filename}.turbodm.part").delete() }
                if (deleteFile && item.filePath.isNotBlank()) {
                    StoragePublisher.deletePublishedFile(appContext, item.filePath)
                }
            }
            dao.deleteById(downloadId)
        }
    }

    fun clearCompleted() {
        scope.launch {
            val completedIds = dao.getAll()
                .filter { it.statusEnum() == DownloadStatus.COMPLETED }
                .map { it.id }
            completedIds.forEach { speedBuffers.remove(it) }
            if (completedIds.isNotEmpty()) {
                _speedHistory.value = _speedHistory.value - completedIds.toSet()
            }
            dao.deleteCompleted()
        }
    }

    suspend fun updateYtDlp(): Result<String> {
        val res = mediaEngine.updateBinary(appContext)
        res.onSuccess { msg ->
            prefs.update {
                it.copy(
                    ytdlpVersion = msg,
                    lastYtdlpUpdateMs = System.currentTimeMillis(),
                )
            }
        }
        return res
    }

    private val liveStates = ConcurrentHashMap<String, DownloadEntity>()

    private fun refreshActiveSnapshot(updated: DownloadEntity?) {
        if (updated != null) {
            if (updated.statusEnum() in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)) {
                liveStates[updated.id] = updated
                if (updated.statusEnum() == DownloadStatus.DOWNLOADING) {
                    val buf = speedBuffers.getOrPut(updated.id) { ArrayDeque() }
                    val snapshot = synchronized(buf) {
                        buf.addLast(updated.speedBytesPerSec.coerceAtLeast(0.0))
                        while (buf.size > 60) buf.removeFirst()
                        buf.toList()
                    }
                    _speedHistory.value = _speedHistory.value + (updated.id to snapshot)
                }
            } else {
                liveStates.remove(updated.id)
            }
        } else {
            liveStates.keys.retainAll(activeTasks.keys)
        }
        val list = liveStates.values.toList()
        _activeSummary.value = list
        DownloadForegroundService.updateNotification(appContext, list)
    }

    private fun scanMediaFile(path: String) {
        runCatching {
            MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
        }
    }
}
