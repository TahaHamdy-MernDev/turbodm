package com.turbodm.android.engine

import android.content.Context
import android.util.Log
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.data.MediaFormatOption
import com.turbodm.android.data.PlaylistVideoEntry
import com.turbodm.android.data.ProbeResult
import com.turbodm.android.data.SegmentState
import com.turbodm.android.data.SegmentStatus
import com.turbodm.android.util.Formatters
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Social media & streaming engine powered by yt-dlp + ffmpeg (youtubedl-android).
 * Supports YouTube, YouTube Playlists, TikTok, Facebook, Instagram, X/Twitter, Reddit, Vimeo, HLS/DASH.
 */
class YtDlpMediaEngine {

    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

    companion object {
        private const val TAG = "YtDlpMediaEngine"

        fun defaultFormatOptions(): List<MediaFormatOption> = listOf(
            MediaFormatOption("bestvideo+bestaudio/best", "Best Available (MP4)", "mp4", false, 9999),
            MediaFormatOption("bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", "1080p Full HD (MP4)", "mp4", false, 1080),
            MediaFormatOption("bestvideo[height<=720]+bestaudio/best[height<=720]/best", "720p HD (MP4)", "mp4", false, 720),
            MediaFormatOption("bestvideo[height<=480]+bestaudio/best[height<=480]/best", "480p Compact (MP4)", "mp4", false, 480),
            MediaFormatOption("bestaudio/best", "Audio Only (MP3)", "mp3", true, 0),
            MediaFormatOption("bestaudio[ext=m4a]/bestaudio/best", "Audio Only (M4A)", "m4a", true, 0),
        )
    }

    suspend fun ensureInitialized(context: Context): Result<String> = initMutex.withLock {
        if (initialized) {
            return Result.success(runCatching { YoutubeDL.getInstance().version(context) ?: "Ready" }.getOrDefault("Ready"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                initialized = true
                YoutubeDL.getInstance().version(context.applicationContext) ?: "Ready"
            }.onFailure {
                Log.e(TAG, "Failed to initialize yt-dlp / ffmpeg", it)
            }
        }
    }

    suspend fun updateBinary(context: Context): Result<String> = withContext(Dispatchers.IO) {
        ensureInitialized(context).onFailure { return@withContext Result.failure(it) }
        runCatching {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE
            )
            val ver = YoutubeDL.getInstance().version(context.applicationContext) ?: "Updated"
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Updated to $ver"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date ($ver)"
                else -> ver
            }
        }
    }

    /**
     * Inspect a single video OR a full playlist (YouTube, TikTok, Facebook, X, Instagram, etc.).
     * Uses --flat-playlist --dump-single-json so even 100-video playlists probe in ~1–2 seconds!
     */
    suspend fun probeMedia(context: Context, url: String): ProbeResult = withContext(Dispatchers.IO) {
        ensureInitialized(context).getOrThrow()

        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "20")
        }

        val response = YoutubeDL.getInstance().execute(request)
        val raw = response.out.trim()
        val jsonStart = raw.indexOf('{')
        if (jsonStart < 0) throw IllegalStateException("No media metadata found for URL")
        val json = JSONObject(raw.substring(jsonStart))

        val entriesArr = json.optJSONArray("entries")
        if (entriesArr != null && entriesArr.length() > 1) {
            val entries = mutableListOf<PlaylistVideoEntry>()
            for (i in 0 until entriesArr.length()) {
                val e = entriesArr.optJSONObject(i) ?: continue
                val id = e.optString("id", "item_$i")
                val title = e.optString("title").takeIf { it.isNotBlank() && it != "[Private video]" && it != "[Deleted video]" }
                    ?: continue
                val entryUrl = e.optString("url").takeIf { it.startsWith("http") }
                    ?: e.optString("webpage_url").takeIf { it.startsWith("http") }
                    ?: "https://www.youtube.com/watch?v=$id"
                val dur = if (e.has("duration") && !e.isNull("duration")) e.optDouble("duration", -1.0).toLong().takeIf { it > 0 } else null
                val thumb = e.optString("thumbnail").ifBlank {
                    e.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url").orEmpty()
                }
                entries.add(PlaylistVideoEntry(id = id, title = title, url = entryUrl, durationSec = dur, thumbnailUrl = thumb))
            }
            if (entries.isNotEmpty()) {
                return@withContext ProbeResult.Playlist(
                    url = url,
                    title = json.optString("title", "Playlist").ifBlank { "Playlist" },
                    uploader = json.optString("uploader", json.optString("channel", "")),
                    entries = entries,
                    formats = defaultFormatOptions(),
                )
            }
        }

        // Single video (possibly wrapped as 1-item playlist entry)
        val itemJson = if (entriesArr != null && entriesArr.length() == 1) {
            entriesArr.optJSONObject(0) ?: json
        } else {
            json
        }

        val title = itemJson.optString("title").ifBlank { Formatters.filenameFromUrl(url, "video") }
        val uploader = itemJson.optString("uploader").ifBlank { itemJson.optString("channel", "") }
        val duration = if (itemJson.has("duration") && !itemJson.isNull("duration")) {
            itemJson.optDouble("duration", -1.0).toLong().takeIf { it > 0 }
        } else null
        val thumbnail = itemJson.optString("thumbnail").ifBlank {
            itemJson.optJSONArray("thumbnails")?.let { arr ->
                if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else ""
            }.orEmpty()
        }

        // Extract available heights if present, otherwise use universal presets
        val dynamicOptions = mutableListOf(
            MediaFormatOption("bestvideo+bestaudio/best", "Best Available (MP4)", "mp4", false, 9999)
        )
        val seenHeights = mutableSetOf<Int>()
        val formatsArr = itemJson.optJSONArray("formats")
        if (formatsArr != null) {
            val heights = mutableListOf<Int>()
            for (i in 0 until formatsArr.length()) {
                val f = formatsArr.optJSONObject(i) ?: continue
                val h = f.optInt("height", 0)
                val vcodec = f.optString("vcodec", "none")
                if (h >= 240 && vcodec != "none" && seenHeights.add(h)) {
                    heights.add(h)
                }
            }
            heights.sortedDescending().take(5).forEach { h ->
                dynamicOptions.add(
                    MediaFormatOption(
                        id = "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best",
                        label = "${h}p Video + Audio (MP4)",
                        ext = "mp4",
                        audioOnly = false,
                        height = h,
                    )
                )
            }
        }
        if (dynamicOptions.size == 1) {
            dynamicOptions.addAll(defaultFormatOptions().drop(1))
        } else {
            dynamicOptions.add(MediaFormatOption("bestaudio/best", "Audio Only (MP3)", "mp3", true, 0))
            dynamicOptions.add(MediaFormatOption("bestaudio[ext=m4a]/bestaudio/best", "Audio Only (M4A)", "m4a", true, 0))
        }

        ProbeResult.SingleMedia(
            url = url,
            title = title,
            uploader = uploader,
            platform = Formatters.detectPlatformLabel(url),
            durationSec = duration,
            thumbnailUrl = thumbnail,
            formats = dynamicOptions,
        )
    }

    suspend fun downloadMedia(
        context: Context,
        entity: DownloadEntity,
        pauseRequested: AtomicBoolean,
        cancelRequested: AtomicBoolean,
        onProgress: suspend (DownloadEntity) -> Unit,
    ): DownloadEntity = withContext(Dispatchers.IO) {
        var current = entity.copy(
            status = DownloadStatus.PROBING.name,
            errorMessage = "",
            resumable = true,
            updatedAt = System.currentTimeMillis(),
        )
        onProgress(current)

        ensureInitialized(context).onFailure { err ->
            current = current.copy(
                status = DownloadStatus.ERROR.name,
                errorMessage = "yt-dlp init failed: ${err.message}",
                updatedAt = System.currentTimeMillis(),
            )
            onProgress(current)
            return@withContext current
        }

        val saveDir = File(current.saveDir).apply { mkdirs() }
        val stem = Formatters.sanitizeFilename(current.filename.substringBeforeLast('.'), "video")
        val outputTemplate = File(saveDir, "$stem.%(ext)s").absolutePath

        val req = YoutubeDLRequest(current.url).apply {
            addOption("--no-playlist")
            addOption("--continue")
            addOption("--no-warnings")
            addOption("--http-chunk-size", "10M") // Anti-throttling for YouTube
            addOption("--concurrent-fragments", current.maxConnections.coerceIn(4, 16).toString())
            addOption("--retries", "5")
            addOption("--fragment-retries", "5")
            addOption("-o", outputTemplate)
            addOption("--print", "after_move:filepath")

            val fmt = current.mediaFormatId.ifBlank { "bestvideo+bestaudio/best" }
            addOption("-f", fmt)

            if (current.mediaAudioOnly) {
                addOption("-x")
                val codec = if (current.filename.endsWith(".m4a", ignoreCase = true)) "m4a" else "mp3"
                addOption("--audio-format", codec)
                addOption("--audio-quality", "0")
                addOption("--embed-metadata")
            } else {
                addOption("--merge-output-format", "mp4")
            }
        }

        current = current.copy(status = DownloadStatus.DOWNLOADING.name, updatedAt = System.currentTimeMillis())
        onProgress(current)

        var lastEmitMs = 0L
        try {
            val response = YoutubeDL.getInstance().execute(req, current.id) { progress, etaInSeconds, line ->
                if (pauseRequested.get() || cancelRequested.get()) {
                    YoutubeDL.getInstance().destroyProcessById(current.id)
                    return@execute
                }
                val now = System.currentTimeMillis()
                if (now - lastEmitMs < 250L && progress < 100f) return@execute
                lastEmitMs = now

                val pct = progress.coerceIn(0f, 100f)
                val parsedSpeed = parseSpeedFromLine(line)
                val parsedTotal = parseTotalSizeFromLine(line) ?: current.totalSize ?: 100L
                val dlBytes = ((pct / 100.0) * parsedTotal).toLong()
                val seg = SegmentState(
                    index = 0,
                    start = 0L,
                    end = (parsedTotal - 1L).coerceAtLeast(0L),
                    downloaded = dlBytes,
                    status = if (pct >= 100f) SegmentStatus.COMPLETED else SegmentStatus.RECEIVING,
                    speedBytesPerSec = parsedSpeed,
                )
                current = current.copy(
                    status = if (line.contains("Merger", true) || line.contains("ExtractAudio", true)) {
                        DownloadStatus.MERGING.name
                    } else {
                        DownloadStatus.DOWNLOADING.name
                    },
                    totalSize = if (parsedTotal > 100L) parsedTotal else current.totalSize,
                    downloadedSize = dlBytes,
                    speedBytesPerSec = parsedSpeed,
                    etaSeconds = if (etaInSeconds >= 0) etaInSeconds else current.etaSeconds,
                    segmentsJson = SegmentState.listToJson(listOf(seg)),
                    updatedAt = now,
                )
                kotlinx.coroutines.runBlocking { onProgress(current) }
            }

            if (cancelRequested.get() || pauseRequested.get()) {
                current = current.copy(
                    status = if (cancelRequested.get()) DownloadStatus.CANCELLED.name else DownloadStatus.PAUSED.name,
                    speedBytesPerSec = 0.0,
                    etaSeconds = null,
                    updatedAt = System.currentTimeMillis(),
                )
                onProgress(current)
                return@withContext current
            }

            // Locate resulting merged/extracted file
            val printedPath = response.out.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.startsWith("/") && File(it).exists() }
            val resultFile = printedPath?.let { File(it) }
                ?: saveDir.listFiles()
                    ?.filter { it.name.startsWith(stem) && !it.name.endsWith(".part") }
                    ?.maxByOrNull { it.lastModified() }

            val finalSize = resultFile?.length() ?: current.totalSize ?: 0L
            val doneSeg = SegmentState(
                index = 0,
                start = 0L,
                end = (finalSize - 1L).coerceAtLeast(0L),
                downloaded = finalSize,
                status = SegmentStatus.COMPLETED,
            )
            current = current.copy(
                filename = resultFile?.name ?: current.filename,
                filePath = resultFile?.absolutePath ?: File(saveDir, current.filename).absolutePath,
                status = DownloadStatus.COMPLETED.name,
                totalSize = finalSize,
                downloadedSize = finalSize,
                speedBytesPerSec = 0.0,
                etaSeconds = 0L,
                segmentsJson = SegmentState.listToJson(listOf(doneSeg)),
                updatedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
            )
            onProgress(current)
            current
        } catch (e: Exception) {
            if (pauseRequested.get() || cancelRequested.get()) {
                current = current.copy(
                    status = if (cancelRequested.get()) DownloadStatus.CANCELLED.name else DownloadStatus.PAUSED.name,
                    speedBytesPerSec = 0.0,
                    etaSeconds = null,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                Log.e(TAG, "Media download error for ${current.url}", e)
                current = current.copy(
                    status = DownloadStatus.ERROR.name,
                    errorMessage = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "Download failed",
                    speedBytesPerSec = 0.0,
                    etaSeconds = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            onProgress(current)
            current
        }
    }

    fun destroyProcess(downloadId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(downloadId) }
    }

    private fun parseSpeedFromLine(line: String?): Double {
        if (line.isNullOrBlank()) return 0.0
        val m = Regex("""at\s+([0-9.]+)\s*(KiB|MiB|GiB|KB|MB|GB|B)/s""", RegexOption.IGNORE_CASE).find(line)
            ?: return 0.0
        val value = m.groupValues[1].toDoubleOrNull() ?: return 0.0
        return when (m.groupValues[2].lowercase()) {
            "gib", "gb" -> value * 1024 * 1024 * 1024
            "mib", "mb" -> value * 1024 * 1024
            "kib", "kb" -> value * 1024
            else -> value
        }
    }

    private fun parseTotalSizeFromLine(line: String?): Long? {
        if (line.isNullOrBlank()) return null
        val m = Regex("""of\s+~?([0-9.]+)\s*(KiB|MiB|GiB|KB|MB|GB|B)""", RegexOption.IGNORE_CASE).find(line)
            ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "gib", "gb" -> (value * 1024 * 1024 * 1024).toLong()
            "mib", "mb" -> (value * 1024 * 1024).toLong()
            "kib", "kb" -> (value * 1024).toLong()
            else -> value.toLong()
        }
    }
}
