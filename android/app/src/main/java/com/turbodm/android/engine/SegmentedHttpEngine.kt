package com.turbodm.android.engine

import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.data.ProbeResult
import com.turbodm.android.data.SegmentState
import com.turbodm.android.data.SegmentStatus
import com.turbodm.android.util.Formatters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * IDM-style multi-part HTTP/HTTPS download engine for Android:
 * - Parallel byte-range connections (up to 32)
 * - Dynamic Segmentation (work-stealing when a segment finishes early)
 * - Atomic positional writes via FileChannel.write(buf, pos) with 1 MB write buffers
 * - Crash-safe pause & resume with ETag validation
 */
class SegmentedHttpEngine(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        const val MIN_SEGMENT_BYTES = 256L * 1024L
        const val DYNAMIC_SPLIT_THRESHOLD = 256L * 1024L
        const val READ_BUFFER_SIZE = 256 * 1024
        const val WRITE_BUFFER_SIZE = 1024 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 TurboDM/1.0"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(32, 2, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun probeDirectFile(url: String): ProbeResult.DirectFile = withContext(Dispatchers.IO) {
        var finalUrl = url
        var totalSize: Long? = null
        var acceptRanges = false
        var etag = ""
        var cdName: String? = null

        // 1. Try HEAD first
        runCatching {
            val req = Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    finalUrl = resp.request.url.toString()
                    etag = resp.header("ETag").orEmpty()
                    cdName = Formatters.parseContentDisposition(resp.header("Content-Disposition"))
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    if (cl != null && cl > 0) totalSize = cl
                    if (resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true) {
                        acceptRanges = true
                    }
                }
            }
        }

        // 2. Fallback 1-byte Range GET to confirm resume capability & total size
        if (totalSize == null || !acceptRanges) {
            runCatching {
                val req = Request.Builder()
                    .url(finalUrl)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 206 || resp.code == 200) {
                        finalUrl = resp.request.url.toString()
                        if (etag.isBlank()) etag = resp.header("ETag").orEmpty()
                        if (cdName == null) {
                            cdName = Formatters.parseContentDisposition(resp.header("Content-Disposition"))
                        }
                        if (resp.code == 206) {
                            acceptRanges = true
                            val cr = resp.header("Content-Range").orEmpty()
                            val parsedTotal = cr.substringAfter('/', "").trim().toLongOrNull()
                            if (parsedTotal != null && parsedTotal > 0) totalSize = parsedTotal
                        } else {
                            acceptRanges = false
                            val cl = resp.header("Content-Length")?.toLongOrNull()
                            if (cl != null && cl > 0) totalSize = cl
                        }
                    }
                }
            }
        }

        val filename = cdName ?: Formatters.filenameFromUrl(finalUrl)
        val category = Formatters.detectCategory(filename)
        ProbeResult.DirectFile(
            url = url,
            finalUrl = finalUrl,
            filename = filename,
            totalSize = totalSize,
            resumable = acceptRanges,
            etag = etag,
            category = category,
        )
    }

    suspend fun download(
        entity: DownloadEntity,
        pauseRequested: AtomicBoolean,
        cancelRequested: AtomicBoolean,
        dynamicSplitEnabled: Boolean = true,
        userAgent: String = USER_AGENT,
        maxRangeChunkBytes: Long = 8L * 1024L * 1024L,
        skipProbe: Boolean = false,
        onProgress: suspend (DownloadEntity) -> Unit,
    ): DownloadEntity = withContext(Dispatchers.IO) {
        var current = entity.copy(
            status = DownloadStatus.PROBING.name,
            errorMessage = "",
            updatedAt = System.currentTimeMillis(),
        )
        onProgress(current)

        val saveDir = File(current.saveDir).apply { mkdirs() }
        val finalFile = File(saveDir, current.filename)
        val partFile = File(saveDir, "${current.filename}.turbodm.part")

        try {
            val existingSegments = current.segments()
            val knownSize = current.totalSize ?: 0L
            val canDirectStart = knownSize > 0L && current.resumable && current.finalUrl.isNotBlank()

            val canResumeExisting: Boolean
            val segments = CopyOnWriteArrayList<SegmentState>()

            if (skipProbe && canDirectStart && existingSegments.isNotEmpty() && partFile.exists()) {
                canResumeExisting = true
                existingSegments.forEach { seg ->
                    if (!seg.isComplete) {
                        seg.status = SegmentStatus.PENDING
                        seg.speedBytesPerSec = 0.0
                        seg.error = ""
                    }
                }
                segments.addAll(existingSegments)
            } else if (existingSegments.isEmpty() && canDirectStart) {
                canResumeExisting = false
                segments.addAll(
                    createInitialSegments(
                        totalSize = current.totalSize,
                        resumable = current.resumable,
                        maxConnections = current.maxConnections.coerceIn(1, 32),
                    )
                )
            } else {
                val probe = probeDirectFile(current.url)
                current = current.copy(
                    finalUrl = probe.finalUrl,
                    resumable = probe.resumable,
                    etag = probe.etag.ifBlank { current.etag },
                )
                canResumeExisting = existingSegments.isNotEmpty() &&
                    partFile.exists() &&
                    probe.resumable &&
                    (probe.totalSize == null || probe.totalSize == current.totalSize) &&
                    (current.etag.isBlank() || probe.etag.isBlank() || current.etag == probe.etag)

                if (canResumeExisting) {
                    existingSegments.forEach { seg ->
                        if (!seg.isComplete) {
                            seg.status = SegmentStatus.PENDING
                            seg.speedBytesPerSec = 0.0
                            seg.error = ""
                        }
                    }
                    segments.addAll(existingSegments)
                } else {
                    current = current.copy(totalSize = probe.totalSize, downloadedSize = 0L)
                    segments.addAll(
                        createInitialSegments(
                            totalSize = probe.totalSize,
                            resumable = probe.resumable,
                            maxConnections = current.maxConnections.coerceIn(1, 32),
                        )
                    )
                }
            }

            if (pauseRequested.get() || cancelRequested.get()) {
                return@withContext finalizeStopped(current, segments, partFile, cancelRequested.get(), onProgress)
            }

            val raf = RandomAccessFile(partFile, "rw")
            if (!canResumeExisting) {
                raf.setLength(current.totalSize ?: 0L)
            }
            val channel = raf.channel
            val fatalError = AtomicReference<String?>(null)
            val nextSegIndex = AtomicInteger((segments.maxOfOrNull { it.index } ?: -1) + 1)

            current = current.copy(
                status = DownloadStatus.DOWNLOADING.name,
                downloadedSize = segments.sumOf { it.downloaded },
                segmentsJson = SegmentState.listToJson(segments),
                updatedAt = System.currentTimeMillis(),
            )
            onProgress(current)

            val speedSamples = ArrayDeque<Pair<Long, Long>>()
            speedSamples.addLast(System.nanoTime() to current.downloadedSize)

            try {
                coordinateSegments(
                    url = current.finalUrl.ifBlank { current.url },
                    resumable = current.resumable,
                    totalSize = current.totalSize,
                    maxConns = current.maxConnections.coerceIn(1, 32),
                    dynamicSplitEnabled = dynamicSplitEnabled,
                    userAgent = userAgent,
                    maxRangeChunkBytes = maxRangeChunkBytes,
                    segments = segments,
                    nextSegIndex = nextSegIndex,
                    channel = channel,
                    pauseRequested = pauseRequested,
                    cancelRequested = cancelRequested,
                    fatalError = fatalError,
                    onTick = {
                        val nowNs = System.nanoTime()
                        val dl = segments.sumOf { it.downloaded }
                        speedSamples.addLast(nowNs to dl)
                        while (speedSamples.size > 2 && (nowNs - speedSamples.first().first) > 2_000_000_000L) {
                            speedSamples.removeFirst()
                        }
                        val (t0, b0) = speedSamples.first()
                        val dtSec = (nowNs - t0) / 1_000_000_000.0
                        val speed = if (dtSec > 0.05) ((dl - b0) / dtSec).coerceAtLeast(0.0) else 0.0
                        val eta = if (current.totalSize != null && speed > 0.0) {
                            ((current.totalSize!! - dl).coerceAtLeast(0L) / speed).toLong()
                        } else null

                        current = current.copy(
                            downloadedSize = dl,
                            speedBytesPerSec = speed,
                            etaSeconds = eta,
                            segmentsJson = SegmentState.listToJson(segments),
                            updatedAt = System.currentTimeMillis(),
                        )
                        onProgress(current)
                    }
                )
            } finally {
                runCatching { channel.force(false) }
                runCatching { channel.close() }
                runCatching { raf.close() }
            }

            if (cancelRequested.get() || pauseRequested.get()) {
                return@withContext finalizeStopped(current, segments, partFile, cancelRequested.get(), onProgress)
            }

            val err = fatalError.get()
            if (err != null) {
                current = current.copy(
                    status = DownloadStatus.ERROR.name,
                    errorMessage = err,
                    speedBytesPerSec = 0.0,
                    etaSeconds = null,
                    downloadedSize = segments.sumOf { it.downloaded },
                    segmentsJson = SegmentState.listToJson(segments),
                    updatedAt = System.currentTimeMillis(),
                )
                onProgress(current)
                return@withContext current
            }

            val totalDownloaded = segments.sumOf { it.downloaded }
            if (current.totalSize != null && totalDownloaded < current.totalSize!!) {
                current = current.copy(
                    status = DownloadStatus.ERROR.name,
                    errorMessage = "Incomplete download: $totalDownloaded / ${current.totalSize}",
                    speedBytesPerSec = 0.0,
                    segmentsJson = SegmentState.listToJson(segments),
                    updatedAt = System.currentTimeMillis(),
                )
                onProgress(current)
                return@withContext current
            }

            val targetFile = uniqueFile(saveDir, current.filename)
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            val finalSize = current.totalSize ?: totalDownloaded
            current = current.copy(
                filename = targetFile.name,
                filePath = targetFile.absolutePath,
                status = DownloadStatus.COMPLETED.name,
                totalSize = finalSize,
                downloadedSize = finalSize,
                speedBytesPerSec = 0.0,
                etaSeconds = 0L,
                segmentsJson = SegmentState.listToJson(segments),
                updatedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
            )
            onProgress(current)
            current
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            current = current.copy(
                status = DownloadStatus.ERROR.name,
                errorMessage = e.message ?: e.javaClass.simpleName,
                speedBytesPerSec = 0.0,
                etaSeconds = null,
                updatedAt = System.currentTimeMillis(),
            )
            onProgress(current)
            current
        }
    }

    private suspend fun coordinateSegments(
        url: String,
        resumable: Boolean,
        totalSize: Long?,
        maxConns: Int,
        dynamicSplitEnabled: Boolean,
        userAgent: String,
        maxRangeChunkBytes: Long,
        segments: CopyOnWriteArrayList<SegmentState>,
        nextSegIndex: AtomicInteger,
        channel: FileChannel,
        pauseRequested: AtomicBoolean,
        cancelRequested: AtomicBoolean,
        fatalError: AtomicReference<String?>,
        onTick: suspend () -> Unit,
    ) = coroutineScope {
        val activeJobs = mutableSetOf<Job>()
        val splitLock = Any()

        fun spawnWorker(seg: SegmentState) {
            val job = launch(Dispatchers.IO) {
                downloadSegment(
                    url = url,
                    resumable = resumable,
                    userAgent = userAgent,
                    maxRangeChunkBytes = maxRangeChunkBytes,
                    seg = seg,
                    channel = channel,
                    splitLock = splitLock,
                    pauseRequested = pauseRequested,
                    cancelRequested = cancelRequested,
                    fatalError = fatalError,
                )
            }
            activeJobs.add(job)
        }

        for (seg in segments) {
            if (!seg.isComplete && activeJobs.size < maxConns) {
                spawnWorker(seg)
            }
        }

        while (activeJobs.isNotEmpty()) {
            delay(200)
            activeJobs.removeAll { it.isCompleted }
            onTick()

            if (pauseRequested.get() || cancelRequested.get() || fatalError.get() != null) {
                activeJobs.forEach { it.cancel() }
                activeJobs.forEach { it.join() }
                break
            }

            // Start pending segments
            for (seg in segments) {
                if (seg.status == SegmentStatus.PENDING && !seg.isComplete && activeJobs.size < maxConns) {
                    spawnWorker(seg)
                }
            }

            // Dynamic Segmentation (IDM work-stealing): split largest active segment in half!
            if (dynamicSplitEnabled) {
                while (resumable && (totalSize ?: 0L) > 0L && activeJobs.size < maxConns) {
                    val newSeg = tryDynamicSplit(segments, nextSegIndex, splitLock) ?: break
                    spawnWorker(newSeg)
                }
            }
        }
    }

    private fun tryDynamicSplit(
        segments: CopyOnWriteArrayList<SegmentState>,
        nextSegIndex: AtomicInteger,
        splitLock: Any,
    ): SegmentState? = synchronized(splitLock) {
        var best: SegmentState? = null
        var bestRemaining = DYNAMIC_SPLIT_THRESHOLD
        for (seg in segments) {
            if ((seg.status == SegmentStatus.RECEIVING || seg.status == SegmentStatus.CONNECTING) && seg.end > 0) {
                val rem = seg.remainingBytes
                if (rem >= bestRemaining) {
                    bestRemaining = rem
                    best = seg
                }
            }
        }
        val target = best ?: return null
        val midpoint = target.currentOffset + (target.remainingBytes / 2L)
        if (midpoint <= target.currentOffset || midpoint >= target.end) return null

        val oldEnd = target.end
        target.end = midpoint
        val spawned = SegmentState(
            index = nextSegIndex.getAndIncrement(),
            start = midpoint + 1L,
            end = oldEnd,
            status = SegmentStatus.PENDING,
        )
        segments.add(spawned)
        spawned
    }

    private suspend fun downloadSegment(
        url: String,
        resumable: Boolean,
        userAgent: String,
        maxRangeChunkBytes: Long,
        seg: SegmentState,
        channel: FileChannel,
        splitLock: Any,
        pauseRequested: AtomicBoolean,
        cancelRequested: AtomicBoolean,
        fatalError: AtomicReference<String?>,
    ) {
        val maxRetries = 10
        var attempt = 0
        val readBuf = ByteArray(READ_BUFFER_SIZE)
        val writeBuf = ByteArray(WRITE_BUFFER_SIZE)

        while (!pauseRequested.get() && !cancelRequested.get() && fatalError.get() == null) {
            if (seg.isComplete) {
                seg.status = SegmentStatus.COMPLETED
                seg.speedBytesPerSec = 0.0
                return
            }

            seg.status = SegmentStatus.CONNECTING
            seg.error = ""
            val isRanged = seg.end >= 0L && resumable
            val reqStart = seg.currentOffset
            val reqEnd = if (isRanged && maxRangeChunkBytes > 0L) {
                minOf(seg.end, reqStart + maxRangeChunkBytes - 1L)
            } else {
                seg.end
            }
            val expectedChunkBytes = if (isRanged) (reqEnd - reqStart + 1L).coerceAtLeast(0L) else -1L
            var bytesReadThisRequest = 0L

            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
            if (isRanged) {
                reqBuilder.header("Range", "bytes=$reqStart-$reqEnd")
            }

            try {
                val call = client.newCall(reqBuilder.build())
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val code = resp.code
                        if (code in listOf(401, 403, 404, 410, 451)) {
                            seg.status = SegmentStatus.ERROR
                            seg.error = "HTTP $code"
                            fatalError.compareAndSet(null, "Server returned HTTP $code")
                            return
                        }
                        throw RuntimeException("HTTP $code")
                    }

                    val body = resp.body ?: throw RuntimeException("Empty response body")
                    val stream = body.byteStream()
                    seg.status = SegmentStatus.RECEIVING

                    var writeBufPos = 0
                    var writeFileOffset = reqStart
                    var windowStartNs = System.nanoTime()
                    var windowBytes = 0L

                    fun flushBuffer() {
                        if (writeBufPos > 0) {
                            val bb = ByteBuffer.wrap(writeBuf, 0, writeBufPos)
                            var offset = writeFileOffset
                            while (bb.hasRemaining()) {
                                val written = channel.write(bb, offset)
                                offset += written
                            }
                            writeFileOffset += writeBufPos
                            writeBufPos = 0
                        }
                    }

                    try {
                        while (!pauseRequested.get() && !cancelRequested.get() && fatalError.get() == null) {
                            val n = stream.read(readBuf)
                            if (n <= 0) break

                            var toTake = n
                            var reachedEnd = false
                            synchronized(splitLock) {
                                if (seg.end >= 0L) {
                                    val chunkRem = if (isRanged) {
                                        minOf(seg.end, reqEnd) - seg.currentOffset + 1L
                                    } else {
                                        seg.end - seg.currentOffset + 1L
                                    }
                                    if (chunkRem <= 0L) {
                                        toTake = 0
                                        reachedEnd = true
                                    } else if (toTake >= chunkRem) {
                                        toTake = chunkRem.toInt()
                                        reachedEnd = true
                                    }
                                }
                                if (toTake > 0) {
                                    seg.downloaded += toTake
                                    bytesReadThisRequest += toTake
                                }
                            }

                            if (toTake > 0) {
                                var srcOffset = 0
                                while (srcOffset < toTake) {
                                    val space = WRITE_BUFFER_SIZE - writeBufPos
                                    val copyLen = minOf(space, toTake - srcOffset)
                                    System.arraycopy(readBuf, srcOffset, writeBuf, writeBufPos, copyLen)
                                    writeBufPos += copyLen
                                    srcOffset += copyLen
                                    if (writeBufPos >= WRITE_BUFFER_SIZE) {
                                        flushBuffer()
                                    }
                                }
                                windowBytes += toTake
                                val nowNs = System.nanoTime()
                                val dt = (nowNs - windowStartNs) / 1_000_000_000.0
                                if (dt >= 0.35) {
                                    seg.speedBytesPerSec = windowBytes / dt
                                    windowStartNs = nowNs
                                    windowBytes = 0L
                                }
                            }

                            if (reachedEnd) break
                        }
                    } finally {
                        flushBuffer()
                    }
                }

                if (pauseRequested.get() || cancelRequested.get()) {
                    seg.status = SegmentStatus.PAUSED
                    seg.speedBytesPerSec = 0.0
                    return
                }
                if (seg.end < 0L || seg.isComplete) {
                    seg.status = SegmentStatus.COMPLETED
                    seg.speedBytesPerSec = 0.0
                    return
                }
                if (isRanged && maxRangeChunkBytes > 0L && bytesReadThisRequest >= expectedChunkBytes) {
                    attempt = 0
                    continue
                }
                throw RuntimeException("Connection closed before segment finished")
            } catch (ce: CancellationException) {
                seg.status = SegmentStatus.PAUSED
                seg.speedBytesPerSec = 0.0
                throw ce
            } catch (e: Exception) {
                attempt++
                seg.speedBytesPerSec = 0.0
                seg.error = e.message ?: "Network error"
                if (attempt > maxRetries) {
                    seg.status = SegmentStatus.ERROR
                    fatalError.compareAndSet(null, "Segment #${seg.index + 1} failed: ${seg.error}")
                    return
                }
                delay((500L * attempt).coerceAtMost(4000L))
            }
        }
    }

    private fun createInitialSegments(
        totalSize: Long?,
        resumable: Boolean,
        maxConnections: Int,
    ): List<SegmentState> {
        if (!resumable || totalSize == null || totalSize <= 0L) {
            return listOf(SegmentState(index = 0, start = 0L, end = -1L))
        }
        val count = (totalSize / MIN_SEGMENT_BYTES).toInt().coerceIn(1, maxConnections)
        if (count <= 1) {
            return listOf(SegmentState(index = 0, start = 0L, end = totalSize - 1L))
        }
        val chunk = totalSize / count
        return List(count) { i ->
            val start = i * chunk
            val end = if (i == count - 1) totalSize - 1L else (start + chunk - 1L)
            SegmentState(index = i, start = start, end = end)
        }
    }

    private suspend fun finalizeStopped(
        entity: DownloadEntity,
        segments: List<SegmentState>,
        partFile: File,
        cancelled: Boolean,
        onProgress: suspend (DownloadEntity) -> Unit,
    ): DownloadEntity {
        segments.forEach { if (!it.isComplete) it.status = SegmentStatus.PAUSED; it.speedBytesPerSec = 0.0 }
        if (cancelled) partFile.delete()
        val updated = entity.copy(
            status = if (cancelled) DownloadStatus.CANCELLED.name else DownloadStatus.PAUSED.name,
            downloadedSize = segments.sumOf { it.downloaded },
            speedBytesPerSec = 0.0,
            etaSeconds = null,
            segmentsJson = SegmentState.listToJson(segments),
            updatedAt = System.currentTimeMillis(),
        )
        onProgress(updated)
        return updated
    }

    private fun uniqueFile(dir: File, filename: String): File {
        val candidate = File(dir, filename)
        if (!candidate.exists()) return candidate
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var n = 1
        while (true) {
            val alt = File(dir, "$stem ($n)$ext")
            if (!alt.exists()) return alt
            n++
        }
    }
}
