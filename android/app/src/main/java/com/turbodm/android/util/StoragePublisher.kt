package com.turbodm.android.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

data class PublishedFile(
    val filePathOrUri: String,
    val displayName: String,
    val sizeBytes: Long,
)

object StoragePublisher {

    fun mimeTypeForFilename(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /**
     * Publishes a verified completed media file from app-private fast work storage
     * into public Downloads/TurboDM/{Category} via Android MediaStore on API 29+ (zero Scoped Storage
     * permission issues on Redmi Note 13 / Android 14), with File fallback on older APIs.
     */
    fun publishToPublicDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        category: String,
        autoSortByCategory: Boolean,
        fallbackSaveDir: File,
    ): PublishedFile {
        val cleanName = Formatters.sanitizeFilename(displayName, sourceFile.name)
        val mimeType = mimeTypeForFilename(cleanName)
        val size = sourceFile.length()
        val relativeSubdir = if (!autoSortByCategory || category.isBlank() || category.equals("General", true)) {
            "TurboDM"
        } else {
            "TurboDM/$category"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            var insertedUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$relativeSubdir"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                insertedUri = uri

                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                        out.flush()
                    }
                } ?: throw IllegalStateException("MediaStore openOutputStream returned null")

                val finalized = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, finalized, null, null)

                val actualName = resolver.query(
                    uri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: cleanName

                return PublishedFile(
                    filePathOrUri = uri.toString(),
                    displayName = actualName,
                    sizeBytes = size,
                )
            } catch (_: Exception) {
                insertedUri?.let { uri ->
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }

        // Legacy API <29 or MediaStore fallback
        fallbackSaveDir.mkdirs()
        val targetFile = uniqueFile(fallbackSaveDir, cleanName)
        if (!sourceFile.renameTo(targetFile)) {
            sourceFile.copyTo(targetFile, overwrite = true)
        }
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
        }
        return PublishedFile(
            filePathOrUri = targetFile.absolutePath,
            displayName = targetFile.name,
            sizeBytes = targetFile.length().takeIf { it > 0L } ?: size,
        )
    }

    fun deletePublishedFile(context: Context, filePathOrUri: String) {
        if (filePathOrUri.isBlank()) return
        if (filePathOrUri.startsWith("content://")) {
            runCatching {
                context.contentResolver.delete(Uri.parse(filePathOrUri), null, null)
            }
        } else {
            runCatching { File(filePathOrUri).delete() }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var idx = 1
        while (candidate.exists()) {
            val next = if (ext.isNotEmpty() && name.contains('.')) "${stem}_($idx).$ext" else "${stem}_($idx)"
            candidate = File(dir, next)
            idx++
        }
        return candidate
    }
}
