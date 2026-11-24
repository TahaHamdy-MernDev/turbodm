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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val concurrencySemaphore = Semaphore(3)

    private data class ActiveControl(
        val job: Job,
        val pauseFlag: AtomicBoolean,
        val cancelFlag: AtomicBoolean,
        val isMedia: Boolean,
    )

    private val activeTasks = ConcurrentHashMap<String, ActiveControl>()

    private val _activeSummary = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val activeSummary: StateFlow<List<DownloadEntity>> = _activeSummary.asStateFlow()

    val downloadsFlow: Flow<List<DownloadEntity>> = dao.observeAll()

    init {
        scope.launch {
            dao.recoverInterruptedAsPaused()
            mediaEngine.ensureInitialized(appContext).onSuccess { ver ->
                prefs.update { it.copy(ytdlpVersion = ver) }
            }
        }
    }

    fun getCategoryDir(category: String): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(publicDownloads, "TurboDM")
        val target = if (category == "General" || category.isBlank()) base else File(base, category)
        return if (target.mkdirs() || target.exists()) {
            target
        } else {
            // Fallback to app-specific external Downloads if public dir unavailable
            File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), category).apply { mkdirs() }
        }
    }

    suspend fun probeUrl(url: String): ProbeResult {
        val trimmed = url.trim()
        return if (Formatters.isMediaOrSocialUrl(trimmed)) {
            mediaEngine.probeMedia(appContext, trimmed)
        } else {
            httpEngine.probeDirectFile(trimmed)
        }
    }

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

        val job = scope.launch {
            val entity = dao.getById(downloadId) ?: return@launch
            DownloadForegroundService.start(appContext)
            try {
                concurrencySemaphore.withPermit {
                    val fresh = dao.getById(downloadId) ?: return@withPermit
                    if (pauseFlag.get() || cancelFlag.get()) return@withPermit

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
                        mediaEngine.downloadMedia(appContext, fresh, pauseFlag, cancelFlag, onProgress)
                    } else {
                        httpEngine.download(fresh, pauseFlag, cancelFlag, onProgress)
                    }
                    dao.upsert(result)

                    if (result.statusEnum() == DownloadStatus.COMPLETED && result.filePath.isNotBlank()) {
                        scanMediaFile(result.filePath)
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

        scope.launch {
            val isMedia = dao.getById(downloadId)?.isMedia ?: false
            activeTasks[downloadId] = ActiveControl(job, pauseFlag, cancelFlag, isMedia)
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
        scope.launch {
            val item = dao.getById(downloadId)
            if (item != null) {
                runCatching { File(item.saveDir, "${item.filename}.turbodm.part").delete() }
                if (deleteFile && item.filePath.isNotBlank()) {
                    runCatching { File(item.filePath).delete() }
                }
            }
            dao.deleteById(downloadId)
        }
    }

    fun clearCompleted() {
        scope.launch { dao.deleteCompleted() }
    }

    suspend fun updateYtDlp(): Result<String> {
        val res = mediaEngine.updateBinary(appContext)
        res.onSuccess { msg ->
            prefs.update { it.copy(ytdlpVersion = msg) }
        }
        return res
    }

    private val liveStates = ConcurrentHashMap<String, DownloadEntity>()

    private fun refreshActiveSnapshot(updated: DownloadEntity?) {
        if (updated != null) {
            if (updated.statusEnum() in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)) {
                liveStates[updated.id] = updated
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
