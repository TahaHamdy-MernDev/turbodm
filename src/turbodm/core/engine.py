"""IDM-style async multi-part HTTP/HTTPS download engine with Dynamic Segmentation."""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

from turbodm.constants import (
    CHUNK_READ_SIZE,
    DEFAULT_USER_AGENT,
    DYNAMIC_SPLIT_THRESHOLD_BYTES,
    MIN_SEGMENT_SIZE_BYTES,
    WRITE_BUFFER_SIZE,
)
from turbodm.core.models import (
    AppSettings,
    DownloadItem,
    DownloadStatus,
    ProbeResult,
    SegmentState,
    SegmentStatus,
)
from turbodm.core.rate_limiter import CompositeRateLimiter, TokenBucketRateLimiter
from turbodm.utils.formatting import (
    compute_file_hash,
    filename_from_url,
    parse_content_disposition,
)

logger = logging.getLogger(__name__)


def build_request_headers(
    item: DownloadItem | None = None,
    settings: AppSettings | None = None,
    extra: dict[str, str] | None = None,
) -> dict[str, str]:
    headers: dict[str, str] = {
        "User-Agent": (
            (item.user_agent if item and item.user_agent else None)
            or (settings.user_agent if settings and settings.user_agent else DEFAULT_USER_AGENT)
        ),
        "Accept": "*/*",
        "Accept-Encoding": "identity",
    }
    if item:
        if item.referer:
            headers["Referer"] = item.referer
        if item.cookies:
            headers["Cookie"] = item.cookies
        for k, v in item.headers.items():
            if k.lower() != "range":
                headers[k] = v
    if extra:
        headers.update(extra)
    return headers


def build_http_client(
    settings: AppSettings,
    item: DownloadItem | None = None,
) -> httpx.AsyncClient:
    timeout = httpx.Timeout(
        connect=settings.connect_timeout,
        read=settings.read_timeout,
        write=30.0,
        pool=settings.connect_timeout,
    )
    auth = None
    if item and item.auth_user:
        auth = httpx.BasicAuth(item.auth_user, item.auth_pass or "")
    proxy = settings.proxy_url if (settings.proxy_enabled and settings.proxy_url) else None
    limits = httpx.Limits(
        max_connections=max(64, settings.default_connections * 4),
        max_keepalive_connections=32,
    )
    return httpx.AsyncClient(
        follow_redirects=True,
        timeout=timeout,
        auth=auth,
        proxy=proxy,
        verify=settings.verify_ssl,
        limits=limits,
        http2=False,  # HTTP/1.1 multi-connection achieves higher per-segment throughput on range servers
    )


async def probe_url(
    url: str,
    settings: AppSettings,
    item: DownloadItem | None = None,
    client: httpx.AsyncClient | None = None,
) -> ProbeResult:
    """Probe URL using HEAD (with Range: bytes=0-0 fallback) to get size, filename, and resume support."""
    headers = build_request_headers(item=item, settings=settings)
    final_url = url
    total_size: int | None = None
    accept_ranges = False
    content_type = ""
    etag = ""
    last_modified = ""
    cd_filename: str | None = None

    owns_client = client is None
    active_client = client or build_http_client(settings, item)
    try:
        # 1. Try HEAD first
        try:
            resp = await active_client.head(url, headers=headers)
            if resp.status_code < 400:
                final_url = str(resp.url)
                content_type = resp.headers.get("content-type", "")
                etag = resp.headers.get("etag", "")
                last_modified = resp.headers.get("last-modified", "")
                cd_filename = parse_content_disposition(resp.headers.get("content-disposition"))
                cl = resp.headers.get("content-length")
                if cl and cl.isdigit():
                    total_size = int(cl)
                ar = resp.headers.get("accept-ranges", "").lower()
                if "bytes" in ar:
                    accept_ranges = True
        except httpx.HTTPError:
            pass

        # 2. Confirm range support or recover missing size/headers via 1-byte Range GET
        if total_size is None or not accept_ranges:
            try:
                range_headers = dict(headers)
                range_headers["Range"] = "bytes=0-0"
                async with active_client.stream("GET", final_url, headers=range_headers) as resp:
                    if resp.status_code in (200, 206):
                        final_url = str(resp.url)
                        content_type = resp.headers.get("content-type", content_type)
                        etag = resp.headers.get("etag", etag)
                        last_modified = resp.headers.get("last-modified", last_modified)
                        cd_filename = cd_filename or parse_content_disposition(
                            resp.headers.get("content-disposition")
                        )
                        if resp.status_code == 206:
                            accept_ranges = True
                            cr = resp.headers.get("content-range", "")
                            # Format: bytes 0-0/123456
                            if "/" in cr:
                                total_part = cr.split("/")[-1].strip()
                                if total_part.isdigit():
                                    total_size = int(total_part)
                        elif resp.status_code == 200:
                            accept_ranges = False
                            cl = resp.headers.get("content-length")
                            if cl and cl.isdigit():
                                total_size = int(cl)
            except httpx.HTTPError:
                pass
    finally:
        if owns_client:
            await active_client.aclose()

    filename = cd_filename or filename_from_url(final_url)
    return ProbeResult(
        url=url,
        final_url=final_url,
        filename=filename,
        total_size=total_size,
        accept_ranges=accept_ranges,
        content_type=content_type,
        etag=etag,
        last_modified=last_modified,
    )


class SegmentedDownloadTask:
    """Executes a single DownloadItem with IDM-style multi-segment downloading & Dynamic Segmentation."""

    def __init__(
        self,
        item: DownloadItem,
        settings: AppSettings,
        global_limiter: TokenBucketRateLimiter,
        on_progress: Callable[[DownloadItem], None] | None = None,
        on_persist: Callable[[DownloadItem], None] | None = None,
    ) -> None:
        self.item = item
        self.settings = settings
        self.local_limiter = TokenBucketRateLimiter(item.speed_limit_kbps * 1024.0)
        self.rate_limiter = CompositeRateLimiter(global_limiter, self.local_limiter)
        self.on_progress = on_progress
        self.on_persist = on_persist

        self._pause_event = asyncio.Event()
        self._cancel_event = asyncio.Event()
        self._fd: int | None = None
        self._active_tasks: set[asyncio.Task[None]] = set()
        self._speed_samples: list[tuple[float, int]] = []
        self._last_ui_emit = 0.0
        self._last_persist = 0.0
        self._next_segment_index = 0
        self._fatal_error: str | None = None

    def request_pause(self) -> None:
        self._pause_event.set()

    def request_cancel(self) -> None:
        self._cancel_event.set()

    def set_speed_limit_kbps(self, kbps: int) -> None:
        self.item.speed_limit_kbps = max(0, int(kbps))
        self.local_limiter.set_rate_kbps(self.item.speed_limit_kbps)

    @property
    def _stopped(self) -> bool:
        return self._pause_event.is_set() or self._cancel_event.is_set()

    async def run(self) -> DownloadStatus:
        """Run or resume the download until completion, pause, cancel, or error."""
        self._pause_event.clear()
        self._cancel_event.clear()
        self._fatal_error = None
        self.item.error_message = ""
        self.item.status = DownloadStatus.PROBING
        self._emit_progress(force=True)

        try:
            async with build_http_client(self.settings, self.item) as client:
                # Step 1: Skip redundant probe round-trip when fresh download was already probed in UI
                already_probed = (
                    not self.item.segments
                    and self.item.total_size is not None
                    and self.item.total_size > 0
                    and self.item.resumable
                    and bool(self.item.final_url)
                )
                if already_probed:
                    can_resume_existing = False
                    self.item.downloaded_size = 0
                    self.item.segments = self._create_initial_segments(
                        total_size=self.item.total_size,
                        accept_ranges=self.item.resumable,
                        max_connections=max(1, min(32, self.item.max_connections)),
                    )
                else:
                    # Reuse the same AsyncClient pool so TLS sessions carry straight into segment downloads
                    probe = await probe_url(self.item.url, self.settings, self.item, client=client)
                    self.item.final_url = probe.final_url
                    if not self.item.filename:
                        self.item.filename = probe.filename
                    self.item.mime_type = probe.content_type or self.item.mime_type

                    # Validate whether existing partial segments are still valid
                    can_resume_existing = (
                        bool(self.item.segments)
                        and self.item.part_filepath.exists()
                        and probe.accept_ranges
                        and (probe.total_size is None or probe.total_size == self.item.total_size)
                        and (not self.item.etag or not probe.etag or self.item.etag == probe.etag)
                    )

                    self.item.resumable = probe.accept_ranges
                    self.item.etag = probe.etag or self.item.etag
                    self.item.last_modified = probe.last_modified or self.item.last_modified

                    if not can_resume_existing:
                        self.item.total_size = probe.total_size
                        self.item.downloaded_size = 0
                        self.item.segments = self._create_initial_segments(
                            total_size=probe.total_size,
                            accept_ranges=probe.accept_ranges,
                            max_connections=max(1, min(32, self.item.max_connections)),
                        )
                    else:
                        # Reset any non-completed segments back to PENDING
                        for seg in self.item.segments:
                            if not seg.is_complete:
                                seg.status = SegmentStatus.PENDING
                                seg.speed = 0.0
                                seg.error = ""
                        self.item.downloaded_size = sum(s.downloaded for s in self.item.segments)

                self._next_segment_index = (
                    max((s.index for s in self.item.segments), default=-1) + 1
                )

                if self._stopped:
                    return self._finalize_stopped_state()

                # Step 2: Prepare destination & part file
                Path(self.item.save_dir).mkdir(parents=True, exist_ok=True)
                part_path = self.item.part_filepath
                flags = os.O_CREAT | os.O_RDWR
                if not can_resume_existing:
                    flags |= os.O_TRUNC
                self._fd = os.open(str(part_path), flags, 0o644)

                # Pre-size sparse file if known size and fresh download (prevents fragmentation)
                if not can_resume_existing and self.item.total_size and self.item.total_size > 0:
                    try:
                        os.ftruncate(self._fd, self.item.total_size)
                    except OSError:
                        pass

                self.item.status = DownloadStatus.DOWNLOADING
                self._speed_samples = [(time.monotonic(), self.item.downloaded_size)]
                self._emit_progress(force=True)
                self._persist(force=True)

                # Step 3: Launch parallel segment workers + dynamic segmentation loop (same pooled client!)
                await self._coordinate_segments(client)

            # Close FD before renaming/verifying
            self._close_fd()

            if self._cancel_event.is_set():
                self.item.status = DownloadStatus.CANCELLED
                self.item.speed = 0.0
                self.item.eta = None
                self._remove_part_file()
                self._emit_progress(force=True)
                self._persist(force=True)
                return self.item.status

            if self._pause_event.is_set():
                return self._finalize_stopped_state()

            if self._fatal_error:
                self.item.status = DownloadStatus.ERROR
                self.item.error_message = self._fatal_error
                self.item.speed = 0.0
                self.item.eta = None
                self._emit_progress(force=True)
                self._persist(force=True)
                return self.item.status

            # Step 4: Verify completeness & atomically move part file -> final path
            if self.item.total_size is not None:
                actual_downloaded = sum(s.downloaded for s in self.item.segments)
                if actual_downloaded < self.item.total_size:
                    self.item.status = DownloadStatus.ERROR
                    self.item.error_message = (
                        f"Incomplete transfer: {actual_downloaded}/{self.item.total_size} bytes"
                    )
                    self._emit_progress(force=True)
                    self._persist(force=True)
                    return self.item.status
                self.item.downloaded_size = self.item.total_size
            else:
                self.item.total_size = self.item.downloaded_size

            # Optional checksum check
            if self.item.checksum_expected:
                actual_hash = await asyncio.to_thread(
                    compute_file_hash, part_path, self.item.checksum_algo
                )
                expected = self.item.checksum_expected.strip().lower()
                self.item.checksum_verified = actual_hash.lower() == expected
                if not self.item.checksum_verified:
                    self.item.status = DownloadStatus.ERROR
                    self.item.error_message = (
                        f"{self.item.checksum_algo.upper()} mismatch: expected {expected[:12]}..., "
                        f"got {actual_hash[:12]}..."
                    )
                    self._emit_progress(force=True)
                    self._persist(force=True)
                    return self.item.status

            os.replace(part_path, self.item.filepath)
            self.item.status = DownloadStatus.COMPLETED
            self.item.speed = 0.0
            self.item.eta = 0.0
            self.item.completed_at = time.time()
            self._emit_progress(force=True)
            self._persist(force=True)
            return self.item.status

        except Exception as exc:
            logger.exception("Download %s failed unexpectedly", self.item.id)
            self._close_fd()
            if self._cancel_event.is_set():
                self.item.status = DownloadStatus.CANCELLED
                self._remove_part_file()
            elif self._pause_event.is_set():
                return self._finalize_stopped_state()
            else:
                self.item.status = DownloadStatus.ERROR
                self.item.error_message = str(exc)
            self.item.speed = 0.0
            self.item.eta = None
            self._emit_progress(force=True)
            self._persist(force=True)
            return self.item.status
        finally:
            self._close_fd()

    def _create_initial_segments(
        self,
        total_size: int | None,
        accept_ranges: bool,
        max_connections: int,
    ) -> list[SegmentState]:
        if not accept_ranges or total_size is None or total_size <= 0:
            return [SegmentState(index=0, start=0, end=-1)]

        # Don't create tiny segments below MIN_SEGMENT_SIZE_BYTES
        num_segments = max(1, min(max_connections, total_size // MIN_SEGMENT_SIZE_BYTES))
        if num_segments <= 1:
            return [SegmentState(index=0, start=0, end=total_size - 1)]

        chunk_size = total_size // num_segments
        segments: list[SegmentState] = []
        for i in range(num_segments):
            start = i * chunk_size
            end = (total_size - 1) if i == num_segments - 1 else (start + chunk_size - 1)
            segments.append(SegmentState(index=i, start=start, end=end))
        return segments

    async def _coordinate_segments(self, client: httpx.AsyncClient) -> None:
        """Spawn workers for all unfinished segments and dynamically split slowest segments as workers finish."""
        max_conns = max(1, min(32, self.item.max_connections))

        def spawn_worker(seg: SegmentState) -> None:
            task = asyncio.create_task(self._download_segment(client, seg))
            self._active_tasks.add(task)
            task.add_done_callback(self._active_tasks.discard)

        for seg in list(self.item.segments):
            if not seg.is_complete and len(self._active_tasks) < max_conns:
                spawn_worker(seg)

        while self._active_tasks:
            await asyncio.wait(
                self._active_tasks,
                timeout=0.15,
                return_when=asyncio.FIRST_COMPLETED,
            )
            self._update_metrics()
            self._emit_progress(force=False)
            self._persist(force=False)

            if self._stopped or self._fatal_error:
                for task in list(self._active_tasks):
                    task.cancel()
                if self._active_tasks:
                    await asyncio.gather(*self._active_tasks, return_exceptions=True)
                break

            # Start any pending segments not yet started
            for seg in list(self.item.segments):
                if (
                    seg.status == SegmentStatus.PENDING
                    and not seg.is_complete
                    and len(self._active_tasks) < max_conns
                ):
                    spawn_worker(seg)

            # Continuous Dynamic Segmentation (IDM work-stealing):
            # Keep every free worker slot saturated until the final bytes!
            while (
                self.item.resumable
                and self.item.total_size
                and len(self._active_tasks) < max_conns
                and not self._stopped
                and not self._fatal_error
            ):
                new_seg = self._try_dynamic_split()
                if new_seg is None:
                    break
                spawn_worker(new_seg)

    def _try_dynamic_split(self) -> SegmentState | None:
        """Find the active segment with the most remaining bytes and split its tail into a new segment."""
        best_seg: SegmentState | None = None
        best_remaining = DYNAMIC_SPLIT_THRESHOLD_BYTES

        for seg in self.item.segments:
            if seg.status in (SegmentStatus.RECEIVING, SegmentStatus.CONNECTING) and seg.end > 0:
                rem = seg.remaining_bytes
                if rem >= best_remaining:
                    best_remaining = rem
                    best_seg = seg

        if best_seg is None:
            return None

        # Split remaining range [current_offset, end] into two halves
        midpoint = best_seg.current_offset + (best_seg.remaining_bytes // 2)
        if midpoint <= best_seg.current_offset or midpoint >= best_seg.end:
            return None

        old_end = best_seg.end
        best_seg.end = midpoint  # Active worker reads seg.end dynamically and stops here!

        new_seg = SegmentState(
            index=self._next_segment_index,
            start=midpoint + 1,
            end=old_end,
            status=SegmentStatus.PENDING,
        )
        self._next_segment_index += 1
        self.item.segments.append(new_seg)
        logger.debug(
            "Dynamic split on %s: seg %d truncated to %d, spawned seg %d [%d..%d]",
            self.item.id,
            best_seg.index,
            best_seg.end,
            new_seg.index,
            new_seg.start,
            new_seg.end,
        )
        return new_seg

    async def _download_segment(self, client: httpx.AsyncClient, seg: SegmentState) -> None:
        """Download a single byte range with retries, rate limiting, and dynamic end tracking."""
        max_retries = max(0, self.settings.max_retries)
        attempt = 0

        while not self._stopped and not self._fatal_error:
            if seg.is_complete:
                seg.status = SegmentStatus.COMPLETED
                seg.speed = 0.0
                return

            seg.status = SegmentStatus.CONNECTING
            seg.error = ""
            extra_headers: dict[str, str] = {}
            is_ranged = seg.end >= 0 and self.item.resumable
            if is_ranged:
                extra_headers["Range"] = f"bytes={seg.current_offset}-{seg.end}"

            headers = build_request_headers(
                item=self.item,
                settings=self.settings,
                extra=extra_headers,
            )

            try:
                async with client.stream("GET", self.item.final_url or self.item.url, headers=headers) as resp:
                    if resp.status_code == 416:
                        # Range not satisfiable: if already complete, treat as done
                        if seg.is_complete:
                            seg.status = SegmentStatus.COMPLETED
                            seg.speed = 0.0
                            return
                        raise httpx.HTTPStatusError(
                            "Range Not Satisfiable (416)", request=resp.request, response=resp
                        )

                    if resp.status_code >= 400:
                        raise httpx.HTTPStatusError(
                            f"HTTP {resp.status_code}", request=resp.request, response=resp
                        )

                    # If server ignored Range on a multi-segment download, fall back if seg 0
                    if is_ranged and resp.status_code == 200 and seg.current_offset > 0:
                        raise RuntimeError("Server refused Range request (returned 200 instead of 206)")

                    seg.status = SegmentStatus.RECEIVING
                    seg_window_start = time.monotonic()
                    seg_window_bytes = 0
                    write_buf = bytearray()
                    write_buf_offset = seg.current_offset

                    def _flush_write_buf() -> None:
                        nonlocal write_buf_offset
                        if write_buf and self._fd is not None:
                            os.pwrite(self._fd, write_buf, write_buf_offset)
                            write_buf_offset += len(write_buf)
                            write_buf.clear()

                    try:
                        async for chunk in resp.aiter_bytes(chunk_size=CHUNK_READ_SIZE):
                            if self._stopped or self._fatal_error:
                                _flush_write_buf()
                                seg.status = SegmentStatus.PAUSED
                                seg.speed = 0.0
                                return

                            if not chunk:
                                continue

                            # Respect dynamic segment end (may have been shortened by _try_dynamic_split)
                            write_data = chunk
                            reached_end = False
                            if seg.end >= 0:
                                remaining = seg.end - seg.current_offset + 1
                                if remaining <= 0:
                                    reached_end = True
                                    break
                                if len(write_data) >= remaining:
                                    write_data = write_data[:remaining]
                                    reached_end = True

                            await self.rate_limiter.acquire(len(write_data))

                            if self._fd is None:
                                return
                            write_buf.extend(write_data)
                            written = len(write_data)
                            seg.downloaded += written
                            seg_window_bytes += written

                            if len(write_buf) >= WRITE_BUFFER_SIZE:
                                _flush_write_buf()

                            now = time.monotonic()
                            dt = now - seg_window_start
                            if dt >= 0.35:
                                seg.speed = seg_window_bytes / dt
                                seg_window_start = now
                                seg_window_bytes = 0

                            if reached_end:
                                break
                    finally:
                        # Guarantee any buffered bytes are flushed to disk before pause/error/completion
                        _flush_write_buf()

                # Stream finished cleanly
                if seg.end < 0 or seg.is_complete:
                    seg.status = SegmentStatus.COMPLETED
                    seg.speed = 0.0
                    return
                # Server closed connection early — retry remaining bytes!
                raise httpx.RemoteProtocolError("Server closed stream before segment completed")

            except asyncio.CancelledError:
                seg.status = SegmentStatus.PAUSED
                seg.speed = 0.0
                return
            except Exception as exc:
                attempt += 1
                seg.retries = attempt
                seg.speed = 0.0
                seg.error = str(exc)

                # Don't retry permanent client errors (401/403/404/410)
                if isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code in (
                    401, 403, 404, 410, 451
                ):
                    seg.status = SegmentStatus.ERROR
                    self._fatal_error = f"HTTP {exc.response.status_code}: {exc.response.reason_phrase}"
                    return

                if attempt > max_retries:
                    seg.status = SegmentStatus.ERROR
                    self._fatal_error = f"Segment {seg.index} failed after {max_retries} retries: {exc}"
                    return

                backoff = min(10.0, 0.5 * (2 ** (attempt - 1)))
                logger.warning(
                    "Segment %d of %s error (%s), retrying in %.1fs (%d/%d)",
                    seg.index,
                    self.item.id,
                    exc,
                    backoff,
                    attempt,
                    max_retries,
                )
                try:
                    await asyncio.sleep(backoff)
                except asyncio.CancelledError:
                    seg.status = SegmentStatus.PAUSED
                    return

    def _update_metrics(self) -> None:
        now = time.monotonic()
        total_dl = sum(s.downloaded for s in self.item.segments)
        self.item.downloaded_size = total_dl
        self._speed_samples.append((now, total_dl))
        # Keep 2-second rolling window for smooth IDM-like transfer speed & ETA
        cutoff = now - 2.0
        while len(self._speed_samples) > 2 and self._speed_samples[0][0] < cutoff:
            self._speed_samples.pop(0)
        t0, b0 = self._speed_samples[0]
        dt = now - t0
        if dt > 0.05:
            speed = max(0.0, (total_dl - b0) / dt)
            self.item.speed = speed
            if self.item.total_size and speed > 0:
                rem = max(0, self.item.total_size - total_dl)
                self.item.eta = rem / speed
            else:
                self.item.eta = None
        self.item.updated_at = time.time()

    def _finalize_stopped_state(self) -> DownloadStatus:
        for seg in self.item.segments:
            if not seg.is_complete:
                seg.status = SegmentStatus.PAUSED
            seg.speed = 0.0
        self.item.downloaded_size = sum(s.downloaded for s in self.item.segments)
        self.item.status = (
            DownloadStatus.CANCELLED if self._cancel_event.is_set() else DownloadStatus.PAUSED
        )
        self.item.speed = 0.0
        self.item.eta = None
        if self._cancel_event.is_set():
            self._remove_part_file()
        self._emit_progress(force=True)
        self._persist(force=True)
        return self.item.status

    def _emit_progress(self, force: bool = False) -> None:
        if not self.on_progress:
            return
        now = time.monotonic()
        if force or (now - self._last_ui_emit) >= 0.15:
            self._last_ui_emit = now
            self.on_progress(self.item)

    def _persist(self, force: bool = False) -> None:
        if not self.on_persist:
            return
        now = time.monotonic()
        if force or (now - self._last_persist) >= 1.5:
            self._last_persist = now
            self.on_persist(self.item)

    def _close_fd(self) -> None:
        if self._fd is not None:
            try:
                os.close(self._fd)
            except OSError:
                pass
            self._fd = None

    def _remove_part_file(self) -> None:
        try:
            if self.item.part_filepath.exists():
                self.item.part_filepath.unlink()
        except OSError:
            pass
