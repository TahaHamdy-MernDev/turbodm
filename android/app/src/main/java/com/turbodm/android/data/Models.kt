package com.turbodm.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class DownloadStatus {
    QUEUED,
    PROBING,
    DOWNLOADING,
    MERGING,
    PAUSED,
    COMPLETED,
    ERROR,
    CANCELLED,
}

enum class SegmentStatus {
    PENDING,
    CONNECTING,
    RECEIVING,
    PAUSED,
    COMPLETED,
    ERROR,
}

data class SegmentState(
    val index: Int,
    val start: Long,
    @Volatile var end: Long,
    @Volatile var downloaded: Long = 0L,
    @Volatile var status: SegmentStatus = SegmentStatus.PENDING,
    @Volatile var speedBytesPerSec: Double = 0.0,
    @Volatile var error: String = "",
) {
    val currentOffset: Long get() = start + downloaded
    val totalBytes: Long get() = if (end < 0) -1L else (end - start + 1L).coerceAtLeast(0L)
    val remainingBytes: Long get() = if (end < 0) -1L else (totalBytes - downloaded).coerceAtLeast(0L)
    val isComplete: Boolean
        get() = status == SegmentStatus.COMPLETED || (end >= 0 && downloaded >= totalBytes)

    fun serialize(): String =
        "$index|$start|$end|$downloaded|${status.name}|$speedBytesPerSec|${error.replace("|", " ").replace("\n", " ")}"

    companion object {
        fun deserialize(line: String): SegmentState? {
            val p = line.split("|", limit = 7)
            if (p.size < 6) return null
            return runCatching {
                SegmentState(
                    index = p[0].toInt(),
                    start = p[1].toLong(),
                    end = p[2].toLong(),
                    downloaded = p[3].toLong(),
                    status = runCatching { SegmentStatus.valueOf(p[4]) }.getOrDefault(SegmentStatus.PENDING),
                    speedBytesPerSec = p[5].toDoubleOrNull() ?: 0.0,
                    error = p.getOrNull(6).orEmpty(),
                )
            }.getOrNull()
        }

        fun listToJson(list: List<SegmentState>): String =
            list.joinToString("\n") { it.serialize() }

        fun listFromJson(raw: String): List<SegmentState> {
            if (raw.isBlank() || raw.trim() == "[]") return emptyList()
            return raw.lineSequence().mapNotNull { deserialize(it.trim()) }.toList()
        }
    }
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    val url: String,
    val finalUrl: String = url,
    val filename: String,
    val saveDir: String,
    val filePath: String = "",
    val category: String = "General",
    val status: String = DownloadStatus.QUEUED.name,
    val totalSize: Long? = null,
    val downloadedSize: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val etaSeconds: Long? = null,
    val resumable: Boolean = false,
    val maxConnections: Int = 16,
    val etag: String = "",
    val isMedia: Boolean = false,
    val mediaFormatId: String = "",
    val mediaAudioOnly: Boolean = false,
    val thumbnailUrl: String = "",
    val platform: String = "Direct File",
    val errorMessage: String = "",
    val segmentsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
) {
    fun statusEnum(): DownloadStatus =
        runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED)

    fun segments(): List<SegmentState> = SegmentState.listFromJson(segmentsJson)
}

data class MediaFormatOption(
    val id: String,
    val label: String,
    val ext: String,
    val audioOnly: Boolean = false,
    val height: Int = 0,
)

data class PlaylistVideoEntry(
    val id: String,
    val title: String,
    val url: String,
    val durationSec: Long? = null,
    val thumbnailUrl: String = "",
)

sealed class ProbeResult {
    data class DirectFile(
        val url: String,
        val finalUrl: String,
        val filename: String,
        val totalSize: Long?,
        val resumable: Boolean,
        val etag: String = "",
        val category: String = "General",
    ) : ProbeResult()

    data class SingleMedia(
        val url: String,
        val title: String,
        val uploader: String,
        val platform: String,
        val durationSec: Long?,
        val thumbnailUrl: String,
        val formats: List<MediaFormatOption>,
    ) : ProbeResult()

    data class Playlist(
        val url: String,
        val title: String,
        val uploader: String,
        val entries: List<PlaylistVideoEntry>,
        val formats: List<MediaFormatOption>,
    ) : ProbeResult()
}

data class AppPrefs(
    val defaultConnections: Int = 16,
    val maxConcurrent: Int = 3,
    val dynamicSegmentation: Boolean = true,
    val autoSortByCategory: Boolean = true,
    val defaultVideoQuality: String = "1080",
    val audioFormat: String = "mp3",
    val wifiOnly: Boolean = false,
    val ytdlpVersion: String = "Bundled",
)
