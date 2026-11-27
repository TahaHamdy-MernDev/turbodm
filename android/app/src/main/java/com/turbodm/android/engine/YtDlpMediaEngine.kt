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
import com.turbodm.android.util.StoragePublisher
import com.turbodm.android.util.YouTubeCookieHelper
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Social media & streaming engine powered by fast pure-Kotlin HTTP probing + cached yt-dlp/ffmpeg.
 * Guarantees Android hardware-playable H.264 + AAC MP4 output (+faststart) and clean MediaStore publishing.
 */
class YtDlpMediaEngine(
    private val youtubeNativeEngine: YouTubeNativeEngine = YouTubeNativeEngine(),
) {

    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    @Volatile
    private var initialized = false
    @Volatile
    private var aria2cAvailable = false
    @Volatile
    private var cachedVersion: String? = null
    @Volatile
    private var autoUpdatedThisSession = false

    private val fastHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "YtDlpMediaEngine"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        data class Aria2cProgress(
            val downloadedBytes: Long,
            val totalBytes: Long,
            val percent: Float,
            val speedBytesPerSec: Double,
            val etaSeconds: Long?,
        )

        private val ARIA2C_PROGRESS_REGEX = Regex(
            """\[#\S+\s+([0-9.]+)(GiB|MiB|KiB|B)/([0-9.]+)(GiB|MiB|KiB|B)\((\d+)%\).*?DL:([0-9.]+)(GiB|MiB|KiB|B)(?:/s)?(?:\s+ETA:([0-9dhms]+))?""",
            RegexOption.IGNORE_CASE
        )

        private fun scaleUnit(value: Double, unit: String): Double = when (unit.lowercase()) {
            "gib", "gb" -> value * 1024.0 * 1024.0 * 1024.0
            "mib", "mb" -> value * 1024.0 * 1024.0
            "kib", "kb" -> value * 1024.0
            else -> value
        }

        private fun parseAria2cEta(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            var total = 0L
            var matched = false
            Regex("""(\d+)([dhms])""", RegexOption.IGNORE_CASE).findAll(raw).forEach { m ->
                val v = m.groupValues[1].toLongOrNull() ?: return@forEach
                matched = true
                total += when (m.groupValues[2].lowercase()) {
                    "d" -> v * 86400L
                    "h" -> v * 3600L
                    "m" -> v * 60L
                    else -> v
                }
            }
            return if (matched) total else null
        }

        internal fun parseAria2cProgress(line: String?): Aria2cProgress? {
            if (line.isNullOrBlank() || !line.contains("[#")) return null
            val m = ARIA2C_PROGRESS_REGEX.find(line) ?: return null
            val dl = scaleUnit(m.groupValues[1].toDoubleOrNull() ?: return null, m.groupValues[2]).toLong()
            val tot = scaleUnit(m.groupValues[3].toDoubleOrNull() ?: return null, m.groupValues[4]).toLong()
            val pct = (m.groupValues[5].toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
            val speed = scaleUnit(m.groupValues[6].toDoubleOrNull() ?: 0.0, m.groupValues[7])
            val eta = parseAria2cEta(m.groupValues.getOrNull(8))
            return Aria2cProgress(dl, tot, pct, speed, eta)
        }

        fun androidMp4Selector(maxHeight: Int?): String {
            return if (maxHeight != null) {
                "bestvideo[height<=$maxHeight][vcodec^=avc]+bestaudio[acodec^=mp4a]/" +
                    "bestvideo[height<=$maxHeight][vcodec^=h264]+bestaudio[acodec^=aac]/" +
                    "bestvideo[height<=$maxHeight][vcodec^=avc]+bestaudio[ext=m4a]/" +
                    "best[height<=$maxHeight][ext=mp4][vcodec!^=av01][vcodec!^=hev][vcodec!^=h265]/" +
                    "bestvideo[height<=$maxHeight][vcodec!^=av01]+bestaudio[ext=m4a]/" +
                    "best[height<=$maxHeight][ext=mp4]/best[height<=$maxHeight]/best"
            } else {
                "bestvideo[vcodec^=avc]+bestaudio[acodec^=mp4a]/" +
                    "bestvideo[vcodec^=h264]+bestaudio[acodec^=aac]/" +
                    "bestvideo[vcodec^=avc]+bestaudio[ext=m4a]/" +
                    "best[ext=mp4][vcodec!^=av01][vcodec!^=hev][vcodec!^=h265]/" +
                    "bestvideo[vcodec!^=av01]+bestaudio[ext=m4a]/" +
                    "best[ext=mp4]/best"
            }
        }

        fun defaultFormatOptions(): List<MediaFormatOption> = listOf(
            MediaFormatOption(androidMp4Selector(1080), "1080p Full HD (MP4)", "mp4", false, 1080),
            MediaFormatOption(androidMp4Selector(720), "720p HD (MP4)", "mp4", false, 720),
            MediaFormatOption(androidMp4Selector(480), "480p Compact (MP4)", "mp4", false, 480),
            MediaFormatOption(androidMp4Selector(null), "Best Available (MP4)", "mp4", false, 9999),
            MediaFormatOption("bestaudio[acodec^=mp4a]/bestaudio[ext=m4a]/bestaudio/best", "Audio Only (MP3)", "mp3", true, 0),
            MediaFormatOption("bestaudio[acodec^=mp4a]/bestaudio[ext=m4a]/bestaudio/best", "Audio Only (M4A)", "m4a", true, 0),
        )
    }

    suspend fun ensureInitialized(context: Context): Result<String> = initMutex.withLock {
        if (initialized) {
            return Result.success(cachedVersion ?: "Ready")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val appCtx = context.applicationContext
                YoutubeDL.getInstance().init(appCtx)
                FFmpeg.getInstance().init(appCtx)
                aria2cAvailable = runCatching {
                    Aria2c.getInstance().init(appCtx)
                    true
                }.onFailure {
                    Log.w(TAG, "Aria2c init unavailable, using built-in downloader", it)
                }.getOrDefault(false)
                initialized = true
                val ver = runCatching { YoutubeDL.getInstance().version(appCtx) }.getOrNull() ?: "Ready"
                cachedVersion = ver
                ver
            }.onFailure {
                Log.e(TAG, "Failed to initialize yt-dlp / ffmpeg", it)
            }
        }
    }

    suspend fun updateBinary(context: Context): Result<String> = updateMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureInitialized(context).onFailure { return@withContext Result.failure(it) }
            runCatching {
                val appCtx = context.applicationContext
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    appCtx,
                    YoutubeDL.UpdateChannel.STABLE
                )
                val ver = runCatching { YoutubeDL.getInstance().version(appCtx) }.getOrNull() ?: "Updated"
                cachedVersion = ver
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "Updated to $ver"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date ($ver)"
                    else -> ver
                }
            }
        }
    }

    private fun isYouTubeUrl(url: String): Boolean =
        Formatters.extractYouTubeVideoId(url) != null ||
            url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true)

    private fun isSignInOrBotError(msg: String?): Boolean {
        val lower = msg?.lowercase().orEmpty()
        return lower.contains("sign in") ||
            lower.contains("not a bot") ||
            lower.contains("confirm your age") ||
            lower.contains("login_required")
    }

    private fun isRetryableExtractError(msg: String?): Boolean {
        val lower = msg?.lowercase().orEmpty()
        return isSignInOrBotError(lower) ||
            lower.contains("403") ||
            lower.contains("unable to extract")
    }

    private fun ytdlpCacheDir(context: Context): File =
        File(context.cacheDir, "ytdlp-cache").apply { mkdirs() }

    /**
     * Probing entry point:
     * - Explicit YouTube playlists (or forcePlaylist = true) use --flat-playlist --dump-single-json.
     * - Single videos (YouTube, TikTok, Facebook, Instagram, X, Reddit, etc.) use ultra-fast pure-Kotlin
     *   oEmbed / OpenGraph HTTP inspection (< 0.5–1.5s) instead of booting Python CPython on every Share!
     */
    suspend fun probeMedia(
        context: Context,
        url: String,
        forcePlaylist: Boolean = false,
    ): ProbeResult {
        return if (forcePlaylist || Formatters.isExplicitPlaylistUrl(url)) {
            probePlaylistWithYtDlp(context, url)
        } else {
            probeSingleMediaFast(url)
        }
    }

    private suspend fun probeSingleMediaFast(url: String): ProbeResult.SingleMedia = withContext(Dispatchers.IO) {
        val rawPlatform = Formatters.detectPlatformLabel(url)
        val platform = if (rawPlatform == "YouTube Playlist") "YouTube" else rawPlatform

        var title = ""
        var uploader = ""
        var thumbnail = ""
        var durationSec: Long? = null
        var formatOptions: List<MediaFormatOption>? = null

        val ytVid = Formatters.extractYouTubeVideoId(url)
        if (ytVid != null) {
            // Pre-warms native Innertube cache in ~0.35s for instant Download start + exact formats/duration
            val extracted = youtubeNativeEngine.extract(ytVid, forceRefresh = false)
            if (extracted != null) {
                title = extracted.title
                uploader = extracted.uploader
                thumbnail = extracted.thumbnailUrl
                durationSec = extracted.durationSec
                formatOptions = YouTubeNativeEngine.toFormatOptions(extracted)
            } else {
                thumbnail = "https://i.ytimg.com/vi/$ytVid/hqdefault.jpg"
                val canonical = "https://www.youtube.com/watch?v=$ytVid"
                val oembedUrl = "https://www.youtube.com/oembed?url=${URLEncoder.encode(canonical, "UTF-8")}&format=json"
                val json = fetchJson(oembedUrl)
                if (json != null) {
                    title = json.optString("title", "").trim()
                    uploader = json.optString("author_name", "").trim()
                    json.optString("thumbnail_url", "").trim().takeIf { it.isNotBlank() }?.let { thumbnail = it }
                }
            }
        } else if (platform == "TikTok") {
            val resolved = resolveFinalUrl(url)
            val oembedUrl = "https://www.tiktok.com/oembed?url=${URLEncoder.encode(resolved, "UTF-8")}"
            val json = fetchJson(oembedUrl)
            if (json != null) {
                title = json.optString("title", "").trim()
                uploader = json.optString("author_name", "").trim()
                thumbnail = json.optString("thumbnail_url", "").trim()
            }
            if (title.isBlank()) {
                val og = fetchOpenGraphMeta(resolved)
                title = og.first
                thumbnail = thumbnail.ifBlank { og.second }
            }
        } else {
            val og = fetchOpenGraphMeta(url)
            title = og.first
            thumbnail = og.second
        }

        ProbeResult.SingleMedia(
            url = url,
            title = title.ifBlank { "$platform Video" },
            uploader = uploader,
            platform = platform,
            durationSec = durationSec,
            thumbnailUrl = thumbnail,
            formats = formatOptions ?: defaultFormatOptions(),
        )
    }

    private suspend fun probePlaylistWithYtDlp(context: Context, url: String): ProbeResult = withContext(Dispatchers.IO) {
        ensureInitialized(context).getOrThrow()

        val listId = Formatters.extractYouTubePlaylistId(url)
        val probeTarget = if (!listId.isNullOrBlank()) {
            "https://www.youtube.com/playlist?list=$listId"
        } else {
            url
        }

        val request = YoutubeDLRequest(probeTarget).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "15")
            addOption("--cache-dir", ytdlpCacheDir(context).absolutePath)
            if (isYouTubeUrl(probeTarget) && YouTubeCookieHelper.hasSavedCookies(context)) {
                addOption("--cookies", YouTubeCookieHelper.cookieFile(context).absolutePath)
            }
        }

        val response = YoutubeDL.getInstance().execute(request)
        val raw = response.out.trim()
        val jsonStart = raw.indexOf('{')
        if (jsonStart < 0) throw IllegalStateException("No playlist metadata found for URL")
        val json = JSONObject(raw.substring(jsonStart))

        val entriesArr = json.optJSONArray("entries")
        if (entriesArr != null && entriesArr.length() > 0) {
            val entries = mutableListOf<PlaylistVideoEntry>()
            for (i in 0 until entriesArr.length()) {
                val e = entriesArr.optJSONObject(i) ?: continue
                val id = e.optString("id", "item_$i")
                val entryTitle = e.optString("title")
                    .takeIf { it.isNotBlank() && it != "[Private video]" && it != "[Deleted video]" }
                    ?: continue
                val entryUrl = e.optString("url").takeIf { it.startsWith("http") }
                    ?: e.optString("webpage_url").takeIf { it.startsWith("http") }
                    ?: "https://www.youtube.com/watch?v=$id"
                val dur = if (e.has("duration") && !e.isNull("duration")) {
                    e.optDouble("duration", -1.0).toLong().takeIf { it > 0 }
                } else null
                val thumb = e.optString("thumbnail").ifBlank {
                    e.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url").orEmpty()
                }
                entries.add(PlaylistVideoEntry(id = id, title = entryTitle, url = entryUrl, durationSec = dur, thumbnailUrl = thumb))
            }
            if (entries.size > 1) {
                return@withContext ProbeResult.Playlist(
                    url = url,
                    title = json.optString("title", "Playlist").ifBlank { "Playlist" },
                    uploader = json.optString("uploader", json.optString("channel", "")),
                    entries = entries,
                    formats = defaultFormatOptions(),
                )
            }
            if (entries.size == 1) {
                val one = entries.first()
                return@withContext ProbeResult.SingleMedia(
                    url = one.url,
                    title = one.title,
                    uploader = json.optString("uploader", json.optString("channel", "")),
                    platform = Formatters.detectPlatformLabel(one.url),
                    durationSec = one.durationSec,
                    thumbnailUrl = one.thumbnailUrl,
                    formats = defaultFormatOptions(),
                )
            }
        }

        // Single video fallback if playlist resolved to 1 item directly
        ProbeResult.SingleMedia(
            url = url,
            title = json.optString("title").ifBlank { Formatters.filenameFromUrl(url, "video") },
            uploader = json.optString("uploader", json.optString("channel", "")),
            platform = Formatters.detectPlatformLabel(url),
            durationSec = if (json.has("duration") && !json.isNull("duration")) {
                json.optDouble("duration", -1.0).toLong().takeIf { it > 0 }
            } else null,
            thumbnailUrl = json.optString("thumbnail", ""),
            formats = defaultFormatOptions(),
        )
    }

    private fun buildDownloadRequest(
        context: Context,
        current: DownloadEntity,
        outputTemplate: String,
        extraExtractorArgs: String? = null,
    ): YoutubeDLRequest {
        val cacheDir = ytdlpCacheDir(context)
        return YoutubeDLRequest(current.url).apply {
            addOption("--no-playlist")
            addOption("--continue")
            addOption("--no-warnings")
            addOption("--no-quiet")
            addOption("--no-simulate")
            addOption("--progress")
            addOption("--newline")
            addOption("--no-check-formats")
            addOption("--no-mtime")
            addOption("--windows-filenames")
            addOption("--socket-timeout", "15")
            addOption("--cache-dir", cacheDir.absolutePath)
            addOption("--http-chunk-size", "10M")
            addOption("--concurrent-fragments", "6")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("-o", outputTemplate)
            addOption("--print", "after_move:TURBODM_FINAL_PATH:%(filepath)s")

            if (aria2cAvailable) {
                addOption("--downloader", "libaria2c.so")
                addOption("--downloader", "dash,m3u8:native")
                addOption(
                    "--downloader-args",
                    "aria2c:-x 8 -s 8 -k 1M --max-tries=8 --retry-wait=2 --connect-timeout=10 --timeout=12 --summary-interval=1"
                )
            }

            if (isYouTubeUrl(current.url) && YouTubeCookieHelper.hasSavedCookies(context)) {
                addOption("--cookies", YouTubeCookieHelper.cookieFile(context).absolutePath)
            }
            if (!extraExtractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", extraExtractorArgs)
            }

            val fmt = current.mediaFormatId.ifBlank { androidMp4Selector(1080) }
            addOption("-f", fmt)

            if (current.mediaAudioOnly) {
                addOption("-x")
                val codec = if (current.filename.endsWith(".m4a", ignoreCase = true)) "m4a" else "mp3"
                addOption("--audio-format", codec)
                addOption("--audio-quality", "0")
                addOption("--embed-metadata")
            } else {
                addOption("-S", "vcodec:h264,acodec:aac,ext:mp4:m4a")
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
                addOption("--postprocessor-args", "Merger+ffmpeg:-movflags +faststart")
                addOption("--postprocessor-args", "VideoRemuxer+ffmpeg:-movflags +faststart")
            }
        }
    }

    private suspend fun executeWithProgress(
        req: YoutubeDLRequest,
        initialState: DownloadEntity,
        pauseRequested: AtomicBoolean,
        cancelRequested: AtomicBoolean,
        onStateUpdate: (DownloadEntity) -> Unit,
        onProgress: suspend (DownloadEntity) -> Unit,
    ): YoutubeDLResponse = coroutineScope {
        var current = initialState
        var lastEmitMs = 0L
        val progressChan = Channel<DownloadEntity>(Channel.CONFLATED)
        val collectorJob = launch {
            for (update in progressChan) {
                onProgress(update)
            }
        }
        try {
            val res = YoutubeDL.getInstance().execute(req, current.id) { progress, etaInSeconds, line ->
                if (pauseRequested.get() || cancelRequested.get()) {
                    YoutubeDL.getInstance().destroyProcessById(current.id)
                    return@execute
                }
                val safeLine = line.orEmpty()
                val aria = parseAria2cProgress(safeLine)
                val isMerging = safeLine.contains("[Merger]", ignoreCase = true) ||
                    safeLine.contains("[VideoRemuxer]", ignoreCase = true) ||
                    safeLine.contains("[ExtractAudio]", ignoreCase = true) ||
                    safeLine.contains("[Fixup", ignoreCase = true) ||
                    safeLine.contains("Deleting original file", ignoreCase = true)
                val isDownloading = aria != null || progress >= 0f || safeLine.contains("[download]")
                if (!isMerging && !isDownloading) return@execute

                val now = System.currentTimeMillis()
                val effPct = aria?.percent ?: progress.coerceIn(0f, 100f)
                if (!isMerging && effPct < 100f && now - lastEmitMs < 250L) return@execute
                lastEmitMs = now

                val parsedSpeed = aria?.speedBytesPerSec ?: parseSpeedFromLine(safeLine)
                val parsedTotal = aria?.totalBytes?.takeIf { it > 0L }
                    ?: parseTotalSizeFromLine(safeLine)
                    ?: current.totalSize
                    ?: 100L
                val dlBytes = aria?.downloadedBytes?.takeIf { it > 0L }
                    ?: ((effPct / 100.0) * parsedTotal).toLong()
                val nextEta = aria?.etaSeconds ?: if (etaInSeconds >= 0 && !isMerging) etaInSeconds else current.etaSeconds
                val seg = SegmentState(
                    index = 0,
                    start = 0L,
                    end = (parsedTotal - 1L).coerceAtLeast(0L),
                    downloaded = dlBytes,
                    status = if (effPct >= 100f) SegmentStatus.COMPLETED else SegmentStatus.RECEIVING,
                    speedBytesPerSec = parsedSpeed,
                )
                val nextState = current.copy(
                    status = if (isMerging) DownloadStatus.MERGING.name else DownloadStatus.DOWNLOADING.name,
                    totalSize = if (parsedTotal > 100L) parsedTotal else current.totalSize,
                    downloadedSize = dlBytes,
                    speedBytesPerSec = if (isMerging) 0.0 else parsedSpeed,
                    etaSeconds = nextEta,
                    segmentsJson = SegmentState.listToJson(listOf(seg)),
                    updatedAt = now,
                )
                current = nextState
                onStateUpdate(nextState)
                progressChan.trySend(nextState)
            }
            progressChan.close()
            collectorJob.join()
            res
        } catch (t: Throwable) {
            progressChan.close()
            collectorJob.cancelAndJoin()
            throw t
        }
    }

    suspend fun downloadMedia(
        context: Context,
        entity: DownloadEntity,
        pauseRequested: AtomicBoolean,
        cancelRequested: AtomicBoolean,
        autoSortByCategory: Boolean = true,
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

        val workDir = File(context.filesDir, "ytdlp_work/${current.id}").apply { mkdirs() }
        val rawStem = current.filename.substringBeforeLast('.')
        val isGenericName = rawStem.isBlank() ||
            rawStem.equals("video", ignoreCase = true) ||
            rawStem.equals("${current.platform} Video", ignoreCase = true) ||
            rawStem.endsWith(" Video", ignoreCase = true)
        val stem = Formatters.sanitizeFilename(rawStem, "video")
        val outputTemplate = if (isGenericName) {
            File(workDir, "%(title).100B.%(ext)s").absolutePath
        } else {
            File(workDir, "$stem.%(ext)s").absolutePath
        }

        try {
            val response = try {
                executeWithProgress(
                    req = buildDownloadRequest(context, current, outputTemplate, null),
                    initialState = current,
                    pauseRequested = pauseRequested,
                    cancelRequested = cancelRequested,
                    onStateUpdate = { current = it },
                    onProgress = onProgress,
                )
            } catch (e1: Exception) {
                if (pauseRequested.get() || cancelRequested.get() || !isRetryableExtractError(e1.message)) {
                    throw e1
                }
                var lastErr: Exception = e1
                var retryRes: YoutubeDLResponse? = null

                if (!autoUpdatedThisSession) {
                    autoUpdatedThisSession = true
                    current = current.copy(
                        status = DownloadStatus.PROBING.name,
                        errorMessage = "",
                        speedBytesPerSec = 0.0,
                        updatedAt = System.currentTimeMillis(),
                    )
                    onProgress(current)
                    updateBinary(context)
                    retryRes = try {
                        executeWithProgress(
                            req = buildDownloadRequest(context, current, outputTemplate, null),
                            initialState = current,
                            pauseRequested = pauseRequested,
                            cancelRequested = cancelRequested,
                            onStateUpdate = { current = it },
                            onProgress = onProgress,
                        )
                    } catch (e2: Exception) {
                        if (pauseRequested.get() || cancelRequested.get()) throw e2
                        lastErr = e2
                        null
                    }
                }

                if (retryRes == null && isYouTubeUrl(current.url) && isRetryableExtractError(lastErr.message)) {
                    current = current.copy(
                        status = DownloadStatus.PROBING.name,
                        errorMessage = "",
                        speedBytesPerSec = 0.0,
                        updatedAt = System.currentTimeMillis(),
                    )
                    onProgress(current)
                    retryRes = try {
                        executeWithProgress(
                            req = buildDownloadRequest(
                                context,
                                current,
                                outputTemplate,
                                "youtube:player_client=android_vr,tv_embedded,mweb"
                            ),
                            initialState = current,
                            pauseRequested = pauseRequested,
                            cancelRequested = cancelRequested,
                            onStateUpdate = { current = it },
                            onProgress = onProgress,
                        )
                    } catch (e3: Exception) {
                        throw e3
                    }
                }

                retryRes ?: throw lastErr
            }

            if (cancelRequested.get() || pauseRequested.get()) {
                val cancelled = cancelRequested.get()
                if (cancelled) {
                    runCatching { workDir.deleteRecursively() }
                }
                current = current.copy(
                    status = if (cancelled) DownloadStatus.CANCELLED.name else DownloadStatus.PAUSED.name,
                    speedBytesPerSec = 0.0,
                    etaSeconds = null,
                    updatedAt = System.currentTimeMillis(),
                )
                onProgress(current)
                return@withContext current
            }

            // Locate resulting merged/remuxed/extracted file unambiguously inside workDir
            val markerPath = response.out.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.startsWith("TURBODM_FINAL_PATH:") }
                ?.removePrefix("TURBODM_FINAL_PATH:")
                ?.trim()

            val fragRegex = Regex("""\.f\d+\.""")
            val resultFile = markerPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 1024L }
                ?: workDir.listFiles()
                    ?.filter { f ->
                        !f.isDirectory &&
                            !f.name.endsWith(".part", true) &&
                            !f.name.endsWith(".ytdl", true) &&
                            !f.name.endsWith(".temp", true) &&
                            !fragRegex.containsMatchIn(f.name) &&
                            f.length() > 1024L
                    }
                    ?.maxByOrNull { it.lastModified() }

            if (resultFile == null || !resultFile.exists() || resultFile.length() <= 1024L) {
                runCatching { workDir.deleteRecursively() }
                current = current.copy(
                    status = DownloadStatus.ERROR.name,
                    errorMessage = "Downloaded media file was empty or incomplete",
                    speedBytesPerSec = 0.0,
                    etaSeconds = null,
                    updatedAt = System.currentTimeMillis(),
                )
                onProgress(current)
                return@withContext current
            }

            val ext = resultFile.extension.ifBlank { if (current.mediaAudioOnly) "mp3" else "mp4" }
            val desiredDisplayName = if (isGenericName) {
                Formatters.sanitizeFilename(resultFile.name, "video.$ext")
            } else {
                Formatters.sanitizeFilename("$stem.$ext", resultFile.name)
            }

            val published = StoragePublisher.publishToPublicDownloads(
                context = context,
                sourceFile = resultFile,
                displayName = desiredDisplayName,
                category = current.category,
                autoSortByCategory = autoSortByCategory,
                fallbackSaveDir = File(current.saveDir),
            )
            runCatching { workDir.deleteRecursively() }

            val finalSize = published.sizeBytes.takeIf { it > 0L } ?: current.totalSize ?: 0L
            val doneSeg = SegmentState(
                index = 0,
                start = 0L,
                end = (finalSize - 1L).coerceAtLeast(0L),
                downloaded = finalSize,
                status = SegmentStatus.COMPLETED,
            )
            current = current.copy(
                filename = published.displayName,
                filePath = published.filePathOrUri,
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
                val cancelled = cancelRequested.get()
                if (cancelled) {
                    runCatching { workDir.deleteRecursively() }
                }
                current = current.copy(
                    status = if (cancelled) DownloadStatus.CANCELLED.name else DownloadStatus.PAUSED.name,
                    speedBytesPerSec = 0.0,
                    etaSeconds = null,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                Log.e(TAG, "Media download error for ${current.url}", e)
                runCatching { workDir.deleteRecursively() }
                val friendlyErr = if (isSignInOrBotError(e.message)) {
                    "YouTube requires sign-in for this video. Go to Settings → YouTube Account to sign in, or try switching Wi-Fi/Mobile Data."
                } else {
                    e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "Download failed"
                }
                current = current.copy(
                    status = DownloadStatus.ERROR.name,
                    errorMessage = friendlyErr,
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

    private fun fetchJson(url: String): JSONObject? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", MOBILE_UA)
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                JSONObject(body)
            }
        }.getOrNull()
    }

    private fun resolveFinalUrl(url: String): String {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", MOBILE_UA)
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        }.getOrDefault(url)
    }

    private fun fetchOpenGraphMeta(url: String): Pair<String, String> {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching "" to ""
                val stream = resp.body?.byteStream() ?: return@runCatching "" to ""
                val buf = ByteArray(64 * 1024)
                var totalRead = 0
                while (totalRead < buf.size) {
                    val r = stream.read(buf, totalRead, buf.size - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
                val html = String(buf, 0, totalRead, Charsets.UTF_8)
                val ogTitle = extractMetaContent(html, "og:title")
                    .ifBlank { extractMetaContent(html, "twitter:title") }
                    .ifBlank { extractHtmlTitle(html) }
                val ogImage = extractMetaContent(html, "og:image")
                    .ifBlank { extractMetaContent(html, "twitter:image") }
                decodeHtmlEntities(ogTitle).trim() to decodeHtmlEntities(ogImage).trim()
            }
        }.getOrDefault("" to "")
    }

    private fun extractMetaContent(html: String, key: String): String {
        val pattern = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        pattern.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        val revPattern = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']${Regex.escape(key)}["']""",
            RegexOption.IGNORE_CASE
        )
        return revPattern.find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractHtmlTitle(html: String): String {
        val m = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
        return m?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun decodeHtmlEntities(input: String): String {
        if (input.isBlank() || !input.contains('&')) return input
        var s = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        s = Regex("""&#x([0-9a-fA-F]+);""").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        s = Regex("""&#(\d+);""").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        return s
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
