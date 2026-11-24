"""End-to-end tests for the multi-part segmented HTTP engine against a real local HTTP server."""

from __future__ import annotations

import asyncio
import hashlib
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from turbodm.core.engine import SegmentedDownloadTask, probe_url
from turbodm.core.models import AppSettings, DownloadItem, DownloadStatus
from turbodm.core.rate_limiter import TokenBucketRateLimiter


class _MockFileServer:
    """Real multi-threaded HTTP server supporting Range, non-Range, throttle, Arabic CD, and redirects."""

    def __init__(self, payload: bytes) -> None:
        self.payload = payload
        self.head_requests = 0
        self.range_requests = 0
        self.slow_first_segment = False
        self.latency_per_chunk = 0.0
        self.lock = threading.Lock()
        parent = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_HEAD(self):
                with parent.lock:
                    parent.head_requests += 1
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/file.bin")
                    self.end_headers()
                    return
                if self.path == "/no-range":
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(parent.payload)))
                    self.end_headers()
                    return
                if self.path == "/arabic":
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(parent.payload)))
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header(
                        "Content-Disposition",
                        "attachment; filename*=UTF-8''%D9%83%D8%AA%D8%A7%D8%A8.pdf",
                    )
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(parent.payload)))
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("ETag", '"turbodm-etag-1"')
                self.end_headers()

            def do_GET(self):
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/file.bin")
                    self.end_headers()
                    return
                if self.path == "/notfound":
                    self.send_response(404)
                    self.end_headers()
                    return

                data = parent.payload
                range_hdr = self.headers.get("Range")
                if self.path != "/no-range" and range_hdr and range_hdr.startswith("bytes="):
                    with parent.lock:
                        parent.range_requests += 1
                    spec = range_hdr.replace("bytes=", "").strip()
                    start_s, end_s = spec.split("-", 1)
                    start = int(start_s)
                    end = int(end_s) if end_s else len(data) - 1
                    end = min(end, len(data) - 1)
                    slice_data = data[start : end + 1]
                    self.send_response(206)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
                    self.send_header("Content-Length", str(len(slice_data)))
                    self.send_header("ETag", '"turbodm-etag-1"')
                    self.end_headers()

                    # Optional slowness on segment starting at byte 0 to trigger dynamic split
                    delay = 0.02 if (parent.slow_first_segment and start == 0) else parent.latency_per_chunk
                    chunk_sz = 32768
                    for i in range(0, len(slice_data), chunk_sz):
                        try:
                            self.wfile.write(slice_data[i : i + chunk_sz])
                            self.wfile.flush()
                        except BrokenPipeError:
                            return
                        if delay > 0:
                            time.sleep(delay)
                    return

                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                chunk_sz = 32768
                for i in range(0, len(data), chunk_sz):
                    try:
                        self.wfile.write(data[i : i + chunk_sz])
                        self.wfile.flush()
                    except BrokenPipeError:
                        return
                    if parent.latency_per_chunk > 0:
                        time.sleep(parent.latency_per_chunk)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def mock_server():
    # 2.5 MB deterministic random payload (triggers multi-part + dynamic splitting cleanly)
    payload = os.urandom(2560 * 1024)
    srv = _MockFileServer(payload)
    yield srv
    srv.close()


@pytest.mark.asyncio
async def test_multipart_download_and_checksum(mock_server: _MockFileServer, tmp_path: Path):
    settings = AppSettings(default_connections=8)
    expected_sha256 = hashlib.sha256(mock_server.payload).hexdigest()

    item = DownloadItem(
        url=f"{mock_server.base_url}/redirect",
        filename="downloaded.bin",
        save_dir=str(tmp_path),
        max_connections=8,
        checksum_expected=expected_sha256,
        checksum_algo="sha256",
    )
    task = SegmentedDownloadTask(item, settings, TokenBucketRateLimiter(0))
    status = await task.run()

    assert status == DownloadStatus.COMPLETED
    assert item.filepath.exists()
    assert item.filepath.read_bytes() == mock_server.payload
    assert item.checksum_verified is True
    assert len(item.segments) >= 4
    assert mock_server.range_requests >= 4


@pytest.mark.asyncio
async def test_dynamic_segmentation_splits_slow_segment(mock_server: _MockFileServer, tmp_path: Path):
    mock_server.slow_first_segment = True
    settings = AppSettings(default_connections=4)
    item = DownloadItem(
        url=f"{mock_server.base_url}/file.bin",
        filename="dynamic.bin",
        save_dir=str(tmp_path),
        max_connections=4,
    )
    task = SegmentedDownloadTask(item, settings, TokenBucketRateLimiter(0))
    status = await task.run()

    assert status == DownloadStatus.COMPLETED
    assert item.filepath.read_bytes() == mock_server.payload
    # Because segment 0 was intentionally slow, faster workers must have stolen work (>4 total segments)
    assert len(item.segments) > 4


@pytest.mark.asyncio
async def test_pause_and_resume_mid_download(mock_server: _MockFileServer, tmp_path: Path):
    mock_server.latency_per_chunk = 0.015
    settings = AppSettings(default_connections=4)
    item = DownloadItem(
        url=f"{mock_server.base_url}/file.bin",
        filename="resumable.bin",
        save_dir=str(tmp_path),
        max_connections=4,
    )
    task1 = SegmentedDownloadTask(item, settings, TokenBucketRateLimiter(0))

    async def _pause_soon():
        await asyncio.sleep(0.12)
        task1.request_pause()

    pause_coro = asyncio.create_task(_pause_soon())
    status1 = await task1.run()
    await pause_coro

    assert status1 == DownloadStatus.PAUSED
    assert item.downloaded_size > 0
    assert item.downloaded_size < len(mock_server.payload)
    assert item.part_filepath.exists()
    bytes_before_resume = item.downloaded_size

    # Resume from saved segments!
    mock_server.latency_per_chunk = 0.0
    task2 = SegmentedDownloadTask(item, settings, TokenBucketRateLimiter(0))
    status2 = await task2.run()

    assert status2 == DownloadStatus.COMPLETED
    assert item.downloaded_size == len(mock_server.payload)
    assert item.filepath.read_bytes() == mock_server.payload
    assert not item.part_filepath.exists()
    assert bytes_before_resume > 0


@pytest.mark.asyncio
async def test_non_range_server_fallback_and_arabic_probe(mock_server: _MockFileServer, tmp_path: Path):
    settings = AppSettings(default_connections=8)

    # Probe Arabic RFC 5987 Content-Disposition
    probe = await probe_url(f"{mock_server.base_url}/arabic", settings)
    assert probe.filename == "كتاب.pdf"
    assert probe.accept_ranges is True
    assert probe.total_size == len(mock_server.payload)

    # Non-range server must cleanly fall back to single connection
    item = DownloadItem(
        url=f"{mock_server.base_url}/no-range",
        filename="single.bin",
        save_dir=str(tmp_path),
        max_connections=8,
    )
    task = SegmentedDownloadTask(item, settings, TokenBucketRateLimiter(0))
    status = await task.run()

    assert status == DownloadStatus.COMPLETED
    assert item.resumable is False
    assert len(item.segments) == 1
    assert item.filepath.read_bytes() == mock_server.payload


@pytest.mark.asyncio
async def test_speed_limiter_and_checksum_failure(tmp_path: Path):
    # Small 300 KB payload with 200 KB/s speed cap -> should take noticeably > 0.5s
    payload = os.urandom(300 * 1024)
    srv = _MockFileServer(payload)
    try:
        settings = AppSettings(default_connections=2)
        limiter = TokenBucketRateLimiter(200 * 1024)  # 200 KB/s
        item = DownloadItem(
            url=f"{srv.base_url}/file.bin",
            filename="limited.bin",
            save_dir=str(tmp_path),
            max_connections=2,
            checksum_expected="0" * 64,  # intentionally wrong
            checksum_algo="sha256",
        )
        t0 = time.monotonic()
        task = SegmentedDownloadTask(item, settings, limiter)
        status = await task.run()
        elapsed = time.monotonic() - t0

        assert status == DownloadStatus.ERROR
        assert item.checksum_verified is False
        assert "mismatch" in item.error_message.lower()
        assert elapsed >= 0.4
    finally:
        srv.close()


@pytest.mark.asyncio
async def test_preprobed_download_skips_redundant_head_and_uses_16_parts(tmp_path: Path):
    # 5 MB payload to exercise 16 parallel segments + 1 MB write buffering + zero redundant HEAD
    payload = os.urandom(5 * 1024 * 1024)
    srv = _MockFileServer(payload)
    try:
        settings = AppSettings()  # default_connections = 16
        assert settings.default_connections == 16
        item = DownloadItem(
            url=f"{srv.base_url}/file.bin",
            final_url=f"{srv.base_url}/file.bin",
            filename="fast16.bin",
            save_dir=str(tmp_path),
            total_size=len(payload),
            resumable=True,
            max_connections=16,
        )
        task = SegmentedDownloadTask(item, settings, TokenBucketRateLimiter(0))
        status = await task.run()

        assert status == DownloadStatus.COMPLETED
        assert srv.head_requests == 0  # Skipped redundant probe round-trip!
        assert len(item.segments) >= 16
        assert item.filepath.read_bytes() == payload
    finally:
        srv.close()
