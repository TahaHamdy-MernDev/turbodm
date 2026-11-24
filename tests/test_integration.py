"""Tests for Queue Scheduler, Local IPC Server, Native Messaging protocol, Media detection, and UI smoke."""

from __future__ import annotations

import datetime
import io
import json
import struct
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from turbodm.core.database import Database
from turbodm.core.manager import DownloadManager
from turbodm.core.models import DownloadItem, DownloadStatus, QueueConfig, SegmentState, SegmentStatus
from turbodm.ipc.native_host import read_native_message, write_native_message
from turbodm.ipc.server import LocalIPCServer, get_or_create_ipc_token
from turbodm.media.ytdlp_engine import is_streaming_url


def test_streaming_url_detection():
    assert is_streaming_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ") is True
    assert is_streaming_url("https://youtu.be/dQw4w9WgXcQ") is True
    assert is_streaming_url("https://cdn.example.com/live/master.m3u8?token=abc") is True
    assert is_streaming_url("https://cdn.example.com/video/manifest.mpd") is True
    assert is_streaming_url("https://example.com/files/ubuntu.iso") is False


def test_scheduler_time_trigger(tmp_path: Path):
    db = Database(tmp_path / "sched.db")
    mgr = DownloadManager(db=db)
    q = QueueConfig(
        id="main",
        name="Main Download Queue",
        max_concurrent=2,
        schedule_enabled=True,
        start_time="02:30",
        stop_time_enabled=True,
        stop_time="05:00",
        days_of_week=[0, 1, 2, 3, 4, 5, 6],
    )
    mgr.save_queue(q)

    # Matching start time should flip queue to running
    mgr.scheduler.check_schedules(datetime.datetime(2026, 11, 23, 2, 30))
    assert mgr.queues["main"].is_running is True

    # Matching stop time should stop queue
    mgr.scheduler.check_schedules(datetime.datetime(2026, 11, 23, 5, 0))
    assert mgr.queues["main"].is_running is False
    db.close()


@pytest.mark.asyncio
async def test_ipc_server_auth_and_endpoints(tmp_path: Path):
    db = Database(tmp_path / "ipc.db")
    mgr = DownloadManager(db=db)
    token = get_or_create_ipc_token(tmp_path / "cfg")
    received_ui: list[dict] = []
    received_batch: list[list[str]] = []

    srv = LocalIPCServer(
        manager=mgr,
        port=0,
        token=token,
        on_incoming_download=lambda payload: received_ui.append(payload),
        on_incoming_batch=lambda urls, _p: received_batch.append(urls),
    )
    await srv.start()
    port = srv.bound_port

    def _call(method: str, path: str, tok: str | None = None, body: dict | None = None):
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}",
            data=json.dumps(body).encode() if body is not None else None,
            headers={
                "Content-Type": "application/json",
                **({"X-TurboDM-Token": tok} if tok else {}),
            },
            method=method,
        )
        with urllib.request.urlopen(req, timeout=3.0) as resp:
            return resp.status, json.loads(resp.read().decode())

    try:
        # Public ping works without token
        st, data = await _to_thread(_call, "GET", "/ping")
        assert st == 200 and data["ok"] is True and data["app"] == "TurboDM"

        # Mutating endpoint rejects missing/bad token
        with pytest.raises(urllib.error.HTTPError) as exc_info:
            await _to_thread(_call, "POST", "/add", "wrong-token", {"url": "https://a.com/x.zip"})
        assert exc_info.value.code == 401

        # Authorized /add triggers incoming download callback
        st, data = await _to_thread(
            _call,
            "POST",
            "/add",
            token,
            {"url": "https://a.com/file.zip", "referer": "https://a.com", "show_dialog": True},
        )
        assert st == 200 and data["queued_ui"] is True
        assert len(received_ui) == 1
        assert received_ui[0]["url"] == "https://a.com/file.zip"

        # Authorized /add_batch
        st, data = await _to_thread(
            _call,
            "POST",
            "/add_batch",
            token,
            {"urls": ["https://a.com/1.zip", "https://a.com/2.zip"]},
        )
        assert st == 200 and data["count"] == 2
        assert received_batch == [["https://a.com/1.zip", "https://a.com/2.zip"]]
    finally:
        await srv.stop()
        db.close()


async def _to_thread(fn, *args):
    import asyncio
    return await asyncio.to_thread(fn, *args)


def test_native_messaging_binary_framing():
    buf = io.BytesIO()
    sample = {"action": "add", "url": "https://example.com/ملف.zip"}
    write_native_message(buf, sample)
    raw = buf.getvalue()
    expected_len = struct.unpack("<I", raw[:4])[0]
    assert expected_len == len(raw) - 4

    buf.seek(0)
    decoded = read_native_message(buf)
    assert decoded == sample


def test_extension_manifests_and_defaults():
    root = Path(__file__).resolve().parent.parent
    chrome_manifest = json.loads((root / "extensions/chrome/manifest.json").read_text())
    ff_manifest = json.loads((root / "extensions/firefox/manifest.json").read_text())

    assert chrome_manifest["manifest_version"] == 3
    assert "key" in chrome_manifest
    assert "nativeMessaging" in chrome_manifest["permissions"]

    assert ff_manifest["manifest_version"] == 2
    assert ff_manifest["browser_specific_settings"]["gecko"]["id"] == "turbodm@turbodm.app"
    assert "nativeMessaging" in ff_manifest["permissions"]

    # Verify autoIntercept is OFF by default as explicitly requested
    bg_js = (root / "extensions/chrome/background.js").read_text()
    assert "autoIntercept: false" in bg_js
    assert "showFloatingButton: true" in bg_js


def test_gtk4_ui_imports_and_cairo_segment_bar():
    """Verify GTK4/Libadwaita UI modules import cleanly and Cairo segment bar renders without error."""
    import cairo
    from turbodm.ui.segment_bar import SegmentProgressBar
    import turbodm.ui.add_dialog  # noqa: F401
    import turbodm.ui.batch_dialog  # noqa: F401
    import turbodm.ui.main_window  # noqa: F401
    import turbodm.ui.progress_dialog  # noqa: F401
    import turbodm.ui.properties_dialog  # noqa: F401
    import turbodm.ui.scheduler_dialog  # noqa: F401
    import turbodm.ui.settings_dialog  # noqa: F401

    bar = SegmentProgressBar(height=28)
    item = DownloadItem(
        filename="test.iso",
        total_size=1000,
        downloaded_size=600,
        status=DownloadStatus.DOWNLOADING,
        segments=[
            SegmentState(index=0, start=0, end=499, downloaded=500, status=SegmentStatus.COMPLETED),
            SegmentState(index=1, start=500, end=999, downloaded=100, status=SegmentStatus.RECEIVING),
        ],
    )
    bar.update_item(item)
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, 400, 28)
    ctx = cairo.Context(surf)
    bar._on_draw(bar, ctx, 400, 28)
