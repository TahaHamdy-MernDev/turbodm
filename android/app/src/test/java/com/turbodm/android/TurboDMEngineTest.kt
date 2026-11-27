package com.turbodm.android

import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.engine.SegmentedHttpEngine
import com.turbodm.android.engine.YouTubeNativeEngine
import com.turbodm.android.engine.YtDlpMediaEngine
import com.turbodm.android.util.Formatters
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun testChunkedMultipartRangeDownloadByteExact() = runBlocking {
        val payload = Random(99).nextBytes(1400 * 1024) // ~1.4 MB -> forces multiple 128 KB sub-chunks per segment
        var headCalls = 0
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    headCalls++
                    return MockResponse().setResponseCode(405)
                }
                val range = request.getHeader("Range")
                if (range != null && range.startsWith("bytes=")) {
                    val parts = range.removePrefix("bytes=").split("-")
                    val start = parts[0].toInt()
                    val end = parts[1].toInt().coerceAtMost(payload.lastIndex)
                    val slice = payload.copyOfRange(start, end + 1)
                    return MockResponse()
                        .setResponseCode(206)
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .addHeader("Content-Length", slice.size.toString())
                        .setBody(Buffer().write(slice))
                }
                return MockResponse().setResponseCode(400)
            }
        }
        server.start()
        val tmpDir = Files.createTempDirectory("turbodm-chunked-test").toFile()
        try {
            val engine = SegmentedHttpEngine()
            val url = server.url("/yt-stream.mp4").toString()
            val entity = DownloadEntity(
                url = url,
                finalUrl = url,
                filename = "yt-stream.mp4",
                saveDir = tmpDir.absolutePath,
                totalSize = payload.size.toLong(),
                resumable = true,
                maxConnections = 4,
            )
            val result = engine.download(
                entity = entity,
                pauseRequested = AtomicBoolean(false),
                cancelRequested = AtomicBoolean(false),
                dynamicSplitEnabled = true,
                maxRangeChunkBytes = 128L * 1024L,
                skipProbe = true,
                onProgress = {},
            )
            assertEquals(DownloadStatus.COMPLETED.name, result.status)
            assertEquals(0, headCalls)
            val savedFile = File(result.filePath)
            assertTrue(savedFile.exists())
            assertArrayEquals(payload, savedFile.readBytes())
        } finally {
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testYouTubeInnertubeParserAndAria2cProgress() {
        val sampleJson = JSONObject(
            """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {
                "videoId": "dQw4w9WgXcQ",
                "title": "Test YouTube HD",
                "author": "Turbo Channel",
                "lengthSeconds": "213",
                "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", "width": 480}]}
              },
              "streamingData": {
                "adaptiveFormats": [
                  {"itag": 399, "url": "https://rr1.googlevideo.com/av1", "mimeType": "video/mp4; codecs=\"av01.0.08M.08\"", "height": 1080, "bitrate": 3500000, "contentLength": "50000000"},
                  {"itag": 137, "url": "https://rr1.googlevideo.com/h264_1080", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "height": 1080, "bitrate": 4000000, "contentLength": "60000000"},
                  {"itag": 136, "url": "https://rr1.googlevideo.com/h264_720", "mimeType": "video/mp4; codecs=\"avc1.4d401f\"", "height": 720, "bitrate": 2000000, "contentLength": "30000000"},
                  {"itag": 140, "url": "https://rr1.googlevideo.com/m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "contentLength": "4000000"},
                  {"itag": 251, "url": "https://rr1.googlevideo.com/opus", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "contentLength": "4500000"}
                ]
              }
            }
            """.trimIndent()
        )
        val extracted = YouTubeNativeEngine.parseInnertubeResponse("dQw4w9WgXcQ", sampleJson)
        assertNotNull(extracted)
        extracted!!
        assertEquals("Test YouTube HD", extracted.title)
        assertEquals(213L, extracted.durationSec)
        assertTrue(extracted.videoStreams.none { it.mimeType.contains("av01") })

        val (v1080, a1080) = YouTubeNativeEngine.selectStreams(
            extracted,
            YtDlpMediaEngine.androidMp4Selector(1080),
            audioOnly = false
        )
        assertEquals(137, v1080?.itag)
        assertEquals(140, a1080?.itag)

        val aria = YtDlpMediaEngine.parseAria2cProgress("[#1a2b3c 12.5MiB/50.0MiB(25%) CN:8 DL:3.8MiB ETA:10s]")
        assertNotNull(aria)
        aria!!
        assertEquals(25f, aria.percent, 0.01f)
        assertEquals(10L, aria.etaSeconds)
        assertTrue(aria.speedBytesPerSec > 3.5 * 1024 * 1024)
        assertTrue(aria.totalBytes > 49 * 1024 * 1024)
    }
}
