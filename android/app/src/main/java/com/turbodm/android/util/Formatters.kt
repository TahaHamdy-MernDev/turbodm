package com.turbodm.android.util

import java.net.URI
import java.net.URLDecoder
import kotlin.math.roundToInt

object Formatters {

    private val URL_REGEX = Regex("""https?://[^\s<>"'\]\)]+""", RegexOption.IGNORE_CASE)

    private val MEDIA_HOSTS = listOf(
        "youtube.com", "youtu.be", "music.youtube.com",
        "tiktok.com", "vm.tiktok.com", "vt.tiktok.com",
        "facebook.com", "fb.watch", "m.facebook.com",
        "instagram.com", "instagr.am",
        "twitter.com", "x.com", "t.co",
        "vimeo.com", "dailymotion.com", "dai.ly",
        "twitch.tv", "clips.twitch.tv",
        "reddit.com", "v.redd.it",
        "soundcloud.com", "bilibili.com", "pinterest.com",
        "snapchat.com", "threads.net", "rumble.com"
    )

    private val STREAM_EXTS = listOf(".m3u8", ".mpd")

    private val CATEGORY_EXTS: Map<String, Set<String>> = mapOf(
        "Compressed" to setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "img"),
        "Documents" to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "md"),
        "Music" to setOf("mp3", "flac", "wav", "aac", "ogg", "opus", "m4a", "wma"),
        "Programs" to setOf("apk", "xapk", "apkm", "rpm", "deb", "exe", "msi", "sh", "bin", "jar"),
        "Video" to setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "m4v", "3gp", "ts", "m3u8", "mpd"),
    )

    /** Extract the first HTTP(S) URL from shared social text like "Watch this! https://vm.tiktok.com/xyz" */
    fun extractUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val match = URL_REGEX.find(rawText.trim()) ?: return null
        return match.value.trimEnd('.', ',', ';', '!', '?')
    }

    fun isMediaOrSocialUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = (uri.host ?: "").lowercase().removePrefix("www.")
            val path = (uri.path ?: "").lowercase()
            if (STREAM_EXTS.any { path.endsWith(it) }) return true
            MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }

    fun isLikelyPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("youtube.com") || lower.contains("youtu.be")) &&
            (lower.contains("/playlist") || lower.contains("list="))
    }

    fun detectPlatformLabel(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") ->
                if (isLikelyPlaylistUrl(url)) "YouTube Playlist" else "YouTube"
            lower.contains("tiktok.com") -> "TikTok"
            lower.contains("facebook.com") || lower.contains("fb.watch") -> "Facebook"
            lower.contains("instagram.com") -> "Instagram"
            lower.contains("x.com") || lower.contains("twitter.com") -> "X / Twitter"
            lower.contains("reddit.com") || lower.contains("redd.it") -> "Reddit"
            lower.contains("vimeo.com") -> "Vimeo"
            lower.contains("twitch.tv") -> "Twitch"
            lower.contains("soundcloud.com") -> "SoundCloud"
            lower.contains(".m3u8") || lower.contains(".mpd") -> "HLS / DASH Stream"
            else -> "Direct File"
        }
    }

    fun detectCategory(filename: String, isMedia: Boolean = false, audioOnly: Boolean = false): String {
        if (isMedia) return if (audioOnly) "Music" else "Video"
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isBlank() || ext == filename.lowercase()) return "General"
        for ((cat, exts) in CATEGORY_EXTS) {
            if (ext in exts) return cat
        }
        return "General"
    }

    fun sanitizeFilename(name: String, fallback: String = "download"): String {
        if (name.isBlank()) return fallback
        val decoded = try {
            URLDecoder.decode(name, "UTF-8")
        } catch (_: Exception) {
            name
        }
        val base = decoded.replace('\\', '/').substringAfterLast('/').trim()
        val cleaned = base.replace(Regex("""[\x00-\x1f\x7f<>:"|?*]"""), "_").trim(' ', '.')
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return fallback
        return if (cleaned.length > 180) cleaned.take(180) else cleaned
    }

    fun filenameFromUrl(url: String, fallback: String = "download"): String {
        return try {
            val uri = URI(url)
            val segment = (uri.path ?: "").trimEnd('/').substringAfterLast('/')
            if (segment.isNotBlank()) sanitizeFilename(segment, fallback)
            else sanitizeFilename(uri.host ?: fallback, fallback)
        } catch (_: Exception) {
            fallback
        }
    }

    fun parseContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val rfc5987 = Regex("""filename\*\s*=\s*[^'\s]+'[^']*'?([^;\r\n]+)""", RegexOption.IGNORE_CASE)
            .find(header)
        if (rfc5987 != null) {
            return sanitizeFilename(rfc5987.groupValues[1].trim().trim('"', '\''))
        }
        val quoted = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(header)
        if (quoted != null) return sanitizeFilename(quoted.groupValues[1])
        val bare = Regex("""filename\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(header)
        if (bare != null) return sanitizeFilename(bare.groupValues[1].trim().trim('"', '\''))
        return null
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return "Unknown"
        var value = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        return if (idx == 0) "${bytes} B" else String.format("%.2f %s", value, units[idx])
    }

    fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec <= 0.0) return "0 B/s"
        return "${formatBytes(bytesPerSec.toLong())}/s"
    }

    fun formatEta(seconds: Long?): String {
        if (seconds == null || seconds < 0 || seconds > 86400 * 30) return "--"
        if (seconds < 60) return "${seconds}s"
        val mins = seconds / 60
        val remSecs = seconds % 60
        if (mins < 60) return "${mins}m ${remSecs}s"
        val hrs = mins / 60
        val remMins = mins % 60
        return "${hrs}h ${remMins}m"
    }

    fun formatDuration(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun progressPercent(downloaded: Long, total: Long?): Float {
        if (total == null || total <= 0L) return 0f
        return ((downloaded.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0).toFloat()
    }

    fun progressFraction(downloaded: Long, total: Long?, status: String): Float {
        if (status == "COMPLETED") return 1f
        if (total == null || total <= 0L) return 0f
        return (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}
