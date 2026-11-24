"""Video & streaming media detection, format inspection, and download engine using yt-dlp + ffmpeg."""

from __future__ import annotations

import asyncio
import logging
import re
import shutil
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import yt_dlp

from turbodm.core.models import (
    AppSettings,
    DownloadItem,
    DownloadStatus,
    SegmentState,
    SegmentStatus,
)
from turbodm.utils.formatting import format_bytes, sanitize_filename

logger = logging.getLogger(__name__)

# Known streaming platforms or manifest extensions handled by yt-dlp + ffmpeg
MEDIA_HOST_PATTERNS = (
    r"(^|\.)youtube\.com$",
    r"(^|\.)youtu\.be$",
    r"(^|\.)vimeo\.com$",
    r"(^|\.)dailymotion\.com$",
    r"(^|\.)twitch\.tv$",
    r"(^|\.)tiktok\.com$",
    r"(^|\.)twitter\.com$",
    r"(^|\.)x\.com$",
    r"(^|\.)instagram\.com$",
    r"(^|\.)facebook\.com$",
    r"(^|\.)fb\.watch$",
    r"(^|\.)reddit\.com$",
    r"(^|\.)soundcloud\.com$",
    r"(^|\.)bilibili\.com$",
)

STREAM_MANIFEST_EXTS = (".m3u8", ".mpd")


def is_streaming_url(url: str) -> bool:
    """Return True if URL points to a video platform or HLS/DASH stream manifest."""
    try:
        parsed = urlparse(url)
    except ValueError:
        return False
    host = (parsed.hostname or "").lower()
    path = (parsed.path or "").lower()
    if any(path.endswith(ext) for ext in STREAM_MANIFEST_EXTS):
        return True
    if any(re.search(pat, host) for pat in MEDIA_HOST_PATTERNS):
        return True
    return False


def probe_media_formats_sync(
    url: str,
    settings: AppSettings,
    referer: str = "",
    cookies: str = "",
    user_agent: str = "",
) -> dict[str, Any]:
    """Synchronously inspect a media/video URL using yt-dlp (call via asyncio.to_thread)."""
    ydl_opts: dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "socket_timeout": settings.connect_timeout,
        "http_headers": {
            "User-Agent": user_agent or settings.user_agent,
        },
    }
    if referer:
        ydl_opts["http_headers"]["Referer"] = referer
    if cookies:
        ydl_opts["http_headers"]["Cookie"] = cookies
    if settings.proxy_enabled and settings.proxy_url:
        ydl_opts["proxy"] = settings.proxy_url

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=False)
        if not info:
            raise RuntimeError("No media information found for URL")

    title = sanitize_filename(str(info.get("title") or "video"), fallback="video")
    duration = info.get("duration")
    thumbnail = info.get("thumbnail") or ""
    raw_formats = info.get("formats") or []

    # Build clean user-facing quality presets (like IDM's video quality selector)
    presets: list[dict[str, Any]] = [
        {
            "format_id": "bestvideo+bestaudio/best",
            "label": "Best Available Quality (Auto MP4/MKV)",
            "ext": "mp4",
            "height": 9999,
            "filesize": None,
            "audio_only": False,
        }
    ]

    seen_heights: set[int] = set()
    video_formats = [
        f for f in raw_formats if f.get("vcodec") and f.get("vcodec") != "none" and f.get("height")
    ]
    video_formats.sort(
        key=lambda f: (int(f.get("height") or 0), float(f.get("tbr") or 0)),
        reverse=True,
    )
    for f in video_formats:
        height = int(f.get("height") or 0)
        if height <= 0 or height in seen_heights:
            continue
        seen_heights.add(height)
        fps = f.get("fps")
        size = f.get("filesize") or f.get("filesize_approx")
        fps_str = f" {int(fps)}fps" if fps and fps > 30 else ""
        size_str = f" (~{format_bytes(size)})" if size else ""
        presets.append(
            {
                "format_id": f"bestvideo[height<={height}]+bestaudio/best[height<={height}]/best",
                "label": f"{height}p{fps_str} Video + Audio{size_str}",
                "ext": "mp4",
                "height": height,
                "filesize": size,
                "audio_only": False,
            }
        )

    # Always include Audio-Only options
    presets.append(
        {
            "format_id": "bestaudio/best",
            "label": "Audio Only (MP3 192kbps)",
            "ext": "mp3",
            "height": 0,
            "filesize": None,
            "audio_only": True,
        }
    )
    presets.append(
        {
            "format_id": "bestaudio[ext=m4a]/bestaudio/best",
            "label": "Audio Only (Best Original / M4A)",
            "ext": "m4a",
            "height": 0,
            "filesize": None,
            "audio_only": True,
        }
    )

    return {
        "title": title,
        "duration": duration,
        "thumbnail": thumbnail,
        "uploader": info.get("uploader") or "",
        "formats": presets,
    }


async def probe_media_formats(
    url: str,
    settings: AppSettings,
    referer: str = "",
    cookies: str = "",
    user_agent: str = "",
) -> dict[str, Any]:
    return await asyncio.to_thread(
        probe_media_formats_sync, url, settings, referer, cookies, user_agent
    )


class _DownloadCancelledError(Exception):
    pass


class YtDlpDownloadTask:
    """Downloads streaming media (YouTube, HLS, DASH, etc.) using yt-dlp + ffmpeg."""

    def __init__(
        self,
        item: DownloadItem,
        settings: AppSettings,
        on_progress: Callable[[DownloadItem], None] | None = None,
        on_persist: Callable[[DownloadItem], None] | None = None,
    ) -> None:
        self.item = item
        self.settings = settings
        self.on_progress = on_progress
        self.on_persist = on_persist
        self._pause_event = threading.Event()
        self._cancel_event = threading.Event()
        self._last_ui_emit = 0.0
        self._final_output_path: Path | None = None

    def request_pause(self) -> None:
        self._pause_event.set()

    def request_cancel(self) -> None:
        self._cancel_event.set()

    def set_speed_limit_kbps(self, kbps: int) -> None:
        self.item.speed_limit_kbps = max(0, int(kbps))

    async def run(self) -> DownloadStatus:
        self._pause_event.clear()
        self._cancel_event.clear()
        self.item.error_message = ""
        self.item.status = DownloadStatus.PROBING
        self.item.resumable = True
        self._emit(force=True)
        return await asyncio.to_thread(self._run_sync)

    def _run_sync(self) -> DownloadStatus:
        save_dir = Path(self.item.save_dir)
        save_dir.mkdir(parents=True, exist_ok=True)
        stem = Path(self.item.filename).stem if self.item.filename else "video"
        stem = sanitize_filename(stem, fallback="video")
        outtmpl = str(save_dir / f"{stem}.%(ext)s")

        ffmpeg_path = shutil.which("ffmpeg")
        fmt = self.item.media_format_id or "bestvideo+bestaudio/best"
        ratelimit = None
        if self.item.speed_limit_kbps > 0:
            ratelimit = self.item.speed_limit_kbps * 1024
        elif self.settings.global_speed_limit_enabled and self.settings.global_speed_limit_kbps > 0:
            ratelimit = self.settings.global_speed_limit_kbps * 1024

        ydl_opts: dict[str, Any] = {
            "format": fmt,
            "outtmpl": outtmpl,
            "noplaylist": True,
            "continuedl": True,
            "http_chunk_size": 10 * 1024 * 1024,  # 10 MB chunks bypass YouTube per-stream throttling
            "buffersize": 1024 * 1024,            # 1 MB socket read buffer
            "concurrent_fragment_downloads": max(4, min(32, self.item.max_connections)),
            "socket_timeout": self.settings.connect_timeout,
            "retries": self.settings.max_retries,
            "fragment_retries": self.settings.max_retries,
            "file_access_retries": 5,
            "quiet": True,
            "no_warnings": True,
            "progress_hooks": [self._progress_hook],
            "postprocessor_hooks": [self._postprocessor_hook],
            "http_headers": {
                "User-Agent": self.item.user_agent or self.settings.user_agent,
            },
        }
        if ffmpeg_path:
            ydl_opts["ffmpeg_location"] = ffmpeg_path
        if self.item.referer:
            ydl_opts["http_headers"]["Referer"] = self.item.referer
        if self.item.cookies:
            ydl_opts["http_headers"]["Cookie"] = self.item.cookies
        if self.settings.proxy_enabled and self.settings.proxy_url:
            ydl_opts["proxy"] = self.settings.proxy_url
        if ratelimit:
            ydl_opts["ratelimit"] = ratelimit

        if self.item.media_audio_only and self.item.filename.lower().endswith(".mp3"):
            ydl_opts["postprocessors"] = [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                }
            ]
        elif not self.item.media_audio_only and ffmpeg_path:
            ydl_opts["merge_output_format"] = "mp4"

        try:
            self.item.status = DownloadStatus.DOWNLOADING
            self._emit(force=True)
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(self.item.url, download=True)
                if info and not self._final_output_path:
                    prepared = Path(ydl.prepare_filename(info))
                    if prepared.exists():
                        self._final_output_path = prepared
                    elif self.item.media_audio_only:
                        mp3_candidate = prepared.with_suffix(".mp3")
                        if mp3_candidate.exists():
                            self._final_output_path = mp3_candidate
                    else:
                        mp4_candidate = prepared.with_suffix(".mp4")
                        if mp4_candidate.exists():
                            self._final_output_path = mp4_candidate

            if self._final_output_path and self._final_output_path.exists():
                self.item.filename = self._final_output_path.name
                self.item.save_dir = str(self._final_output_path.parent)
                size = self._final_output_path.stat().st_size
                self.item.total_size = size
                self.item.downloaded_size = size
                self.item.segments = [
                    SegmentState(
                        index=0,
                        start=0,
                        end=max(0, size - 1),
                        downloaded=size,
                        status=SegmentStatus.COMPLETED,
                    )
                ]

            self.item.status = DownloadStatus.COMPLETED
            self.item.speed = 0.0
            self.item.eta = 0.0
            self.item.completed_at = time.time()
            self._emit(force=True)
            self._persist()
            return self.item.status

        except _DownloadCancelledError:
            self.item.speed = 0.0
            self.item.eta = None
            if self._cancel_event.is_set():
                self.item.status = DownloadStatus.CANCELLED
            else:
                self.item.status = DownloadStatus.PAUSED
            self._emit(force=True)
            self._persist()
            return self.item.status
        except Exception as exc:
            logger.exception("Media download %s failed", self.item.id)
            self.item.status = DownloadStatus.ERROR
            self.item.error_message = str(exc)
            self.item.speed = 0.0
            self.item.eta = None
            self._emit(force=True)
            self._persist()
            return self.item.status

    def _progress_hook(self, d: dict[str, Any]) -> None:
        if self._cancel_event.is_set() or self._pause_event.is_set():
            raise _DownloadCancelledError()

        status = d.get("status")
        if status == "downloading":
            downloaded = int(d.get("downloaded_bytes") or 0)
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            speed = float(d.get("speed") or 0.0)
            eta = d.get("eta")
            self.item.status = DownloadStatus.DOWNLOADING
            self.item.downloaded_size = downloaded
            if total:
                self.item.total_size = int(total)
            self.item.speed = speed
            self.item.eta = float(eta) if eta is not None else None
            self.item.updated_at = time.time()

            # Mirror progress into segment 0 so the UI segment bar animates smoothly
            end_byte = (int(total) - 1) if total else -1
            self.item.segments = [
                SegmentState(
                    index=0,
                    start=0,
                    end=end_byte,
                    downloaded=downloaded,
                    status=SegmentStatus.RECEIVING,
                    speed=speed,
                )
            ]
            self._emit(force=False)
        elif status == "finished":
            fname = d.get("filename")
            if fname:
                p = Path(fname)
                if p.exists():
                    self._final_output_path = p
            self.item.status = DownloadStatus.MERGING
            self.item.speed = 0.0
            self._emit(force=True)

    def _postprocessor_hook(self, d: dict[str, Any]) -> None:
        if self._cancel_event.is_set():
            raise _DownloadCancelledError()
        if d.get("status") == "finished":
            info = d.get("info_dict") or {}
            fp = info.get("filepath")
            if fp and Path(fp).exists():
                self._final_output_path = Path(fp)

    def _emit(self, force: bool = False) -> None:
        if not self.on_progress:
            return
        now = time.monotonic()
        if force or (now - self._last_ui_emit) >= 0.2:
            self._last_ui_emit = now
            self.on_progress(self.item)

    def _persist(self) -> None:
        if self.on_persist:
            self.on_persist(self.item)
