package com.turbodm.android

import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.engine.SegmentedHttpEngine
import com.turbodm.android.engine.YtDlpMediaEngine
import com.turbodm.android.util.Formatters
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class TurboDMEngineTest {

    @Test
    fun testSocialUrlExtractionAndPlatformDetection() {
        val tiktokShare = "Check out this awesome video! https://vm.tiktok.com/ZM6abc123/ #fyp"
        assertEquals("https://vm.tiktok.com/ZM6abc123/", Formatters.extractUrl(tiktokShare))
        assertTrue(Formatters.isMediaOrSocialUrl("https://vm.tiktok.com/ZM6abc123/"))
        assertEquals("TikTok", Formatters.detectPlatformLabel("https://vm.tiktok.com/ZM6abc123/"))

        val ytPlaylist = "https://www.youtube.com/playlist?list=PL1234567890"
        assertTrue(Formatters.isMediaOrSocialUrl(ytPlaylist))
        assertTrue(Formatters.isLikelyPlaylistUrl(ytPlaylist))
        assertEquals("YouTube Playlist", Formatters.detectPlatformLabel(ytPlaylist))

        assertTrue(Formatters.isMediaOrSocialUrl("https://www.facebook.com/reel/123456789"))
        assertTrue(Formatters.isMediaOrSocialUrl("https://x.com/user/status/987654321"))
        assertTrue(Formatters.isMediaOrSocialUrl("https://www.instagram.com/reel/ABC123/"))
        assertTrue(Formatters.isMediaOrSocialUrl("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"))
        assertFalse(Formatters.isMediaOrSocialUrl("https://proof.ovh.net/files/100Mb.dat"))
    }

    @Test
    fun testYouTubeIdAndPlaylistDetectionHelpers() {
        assertEquals("dQw4w9WgXcQ", Formatters.extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Formatters.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=xyz"))
        assertEquals("dQw4w9WgXcQ", Formatters.extractYouTubeVideoId("https://youtube.com/shorts/dQw4w9WgXcQ?feature=share"))
        assertEquals("dQw4w9WgXcQ", Formatters.extractYouTubeVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890"))
        assertEquals(null, Formatters.extractYouTubeVideoId("https://www.youtube.com/playlist?list=PL1234567890"))

        assertTrue(Formatters.isExplicitPlaylistUrl("https://www.youtube.com/playlist?list=PL1234567890"))
        assertFalse(Formatters.isExplicitPlaylistUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890"))
        assertTrue(Formatters.hasDownloadablePlaylistParam("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890"))
        assertFalse(Formatters.hasDownloadablePlaylistParam("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"))

        val formats = YtDlpMediaEngine.defaultFormatOptions()
        assertEquals(1080, formats.first().height)
        assertTrue(formats.first().id.contains("[vcodec^=avc]"))
        assertTrue(formats.first().id.contains("[vcodec!^=av01]"))
        assertTrue(formats.first().id.contains("[vcodec!^=hev]"))
    }

    @Test
    fun testArabicFilenameAndCategoryRouting() {
        val cd = "attachment; filename*=UTF-8''%D9%83%D8%AA%D8%A7%D8%A8_%D8%A8%D8%B1%D9%85%D8%AC%D8%A9.pdf"
        assertEquals("كتاب_برمجة.pdf", Formatters.parseContentDisposition(cd))
        assertEquals("Documents", Formatters.detectCategory("كتاب_برمجة.pdf"))
        assertEquals("Compressed", Formatters.detectCategory("archive.tar.xz"))
        assertEquals("Music", Formatters.detectCategory("song.mp3"))
        assertEquals("Video", Formatters.detectCategory("clip.mp4"))
        assertEquals("Programs", Formatters.detectCategory("app.apk"))
    }

    @Test
    fun testMultipartRangeDownloadByteExact() = runBlocking {
        val payload = Random(42).nextBytes(1200 * 1024) // 1.2 MB -> triggers multi-part segments
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (request.method == "HEAD") {
                    return MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Length", payload.size.toString())
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("ETag", "\"etag-test\"")
                }
                if (range != null && range.startsWith("bytes=")) {
                    val parts = range.removePrefix("bytes=").split("-")
                    val start = parts[0].toInt()
                    val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else payload.lastIndex
                    val clampedEnd = end.coerceAtMost(payload.lastIndex)
                    val slice = payload.copyOfRange(start, clampedEnd + 1)
                    return MockResponse()
                        .setResponseCode(206)
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Range", "bytes $start-$clampedEnd/${payload.size}")
                        .addHeader("Content-Length", slice.size.toString())
                        .setBody(Buffer().write(slice))
                }
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Length", payload.size.toString())
                    .setBody(Buffer().write(payload))
            }
        }
        server.start()
        val tmpDir = Files.createTempDirectory("turbodm-android-test").toFile()
        try {
            val engine = SegmentedHttpEngine()
            val url = server.url("/payload.bin").toString()
            val entity = DownloadEntity(
                url = url,
                filename = "payload.bin",
                saveDir = tmpDir.absolutePath,
                maxConnections = 4,
            )
            val result = engine.download(
                entity = entity,
                pauseRequested = AtomicBoolean(false),
                cancelRequested = AtomicBoolean(false),
                onProgress = {},
            )
            assertEquals(DownloadStatus.COMPLETED.name, result.status)
            val savedFile = File(result.filePath)
            assertTrue(savedFile.exists())
            assertArrayEquals(payload, savedFile.readBytes())
            assertTrue(result.segments().size >= 4)
        } finally {
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }
}
