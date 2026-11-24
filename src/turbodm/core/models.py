"""Core data models for TurboDM."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

from turbodm.constants import DEFAULT_USER_AGENT, IPC_DEFAULT_PORT, get_downloads_root


class DownloadStatus(str, Enum):
    QUEUED = "queued"
    PROBING = "probing"
    DOWNLOADING = "downloading"
    MERGING = "merging"
    PAUSED = "paused"
    COMPLETED = "completed"
    ERROR = "error"
    CANCELLED = "cancelled"


class SegmentStatus(str, Enum):
    PENDING = "pending"
    CONNECTING = "connecting"
    RECEIVING = "receiving"
    PAUSED = "paused"
    COMPLETED = "completed"
    ERROR = "error"


@dataclass
class SegmentState:
    """State of a single byte-range segment in a multi-part download."""

    index: int
    start: int
    end: int  # Inclusive byte index; -1 if unknown size single stream
    downloaded: int = 0
    status: SegmentStatus = SegmentStatus.PENDING
    speed: float = 0.0
    error: str = ""
    retries: int = 0

    @property
    def current_offset(self) -> int:
        return self.start + self.downloaded

    @property
    def total_bytes(self) -> int:
        if self.end < 0:
            return -1
        return max(0, self.end - self.start + 1)

    @property
    def remaining_bytes(self) -> int:
        if self.end < 0:
            return -1
        return max(0, self.total_bytes - self.downloaded)

    @property
    def is_complete(self) -> bool:
        if self.status == SegmentStatus.COMPLETED:
            return True
        if self.end >= 0 and self.downloaded >= self.total_bytes:
            return True
        return False

    def to_dict(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "start": self.start,
            "end": self.end,
            "downloaded": self.downloaded,
            "status": self.status.value,
            "speed": self.speed,
            "error": self.error,
            "retries": self.retries,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> SegmentState:
        return cls(
            index=int(data["index"]),
            start=int(data["start"]),
            end=int(data["end"]),
            downloaded=int(data.get("downloaded", 0)),
            status=SegmentStatus(data.get("status", SegmentStatus.PENDING.value)),
            speed=float(data.get("speed", 0.0)),
            error=str(data.get("error", "")),
            retries=int(data.get("retries", 0)),
        )


@dataclass
class ProbeResult:
    """Result of probing a remote URL before downloading."""

    url: str
    final_url: str
    filename: str
    total_size: int | None
    accept_ranges: bool
    content_type: str = ""
    etag: str = ""
    last_modified: str = ""
    is_media_page: bool = False
    media_title: str = ""
    media_formats: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class DownloadItem:
    """Represents a single download task managed by TurboDM."""

    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    url: str = ""
    final_url: str = ""
    filename: str = ""
    save_dir: str = field(default_factory=lambda: str(get_downloads_root()))
    category: str = "General"
    queue_id: str = "main"
    status: DownloadStatus = DownloadStatus.QUEUED
    total_size: int | None = None
    downloaded_size: int = 0
    speed: float = 0.0
    eta: float | None = None
    resumable: bool = False
    max_connections: int = 16
    speed_limit_kbps: int = 0  # 0 = unlimited (per-download cap)
    description: str = ""
    referer: str = ""
    cookies: str = ""
    user_agent: str = ""
    headers: dict[str, str] = field(default_factory=dict)
    auth_user: str = ""
    auth_pass: str = ""
    etag: str = ""
    last_modified: str = ""
    mime_type: str = ""
    error_message: str = ""
    is_media: bool = False
    media_format_id: str = ""
    media_audio_only: bool = False
    checksum_expected: str = ""
    checksum_algo: str = "sha256"
    checksum_verified: bool | None = None
    on_complete_action: str = "notify"  # notify, open_file, open_folder, command
    on_complete_command: str = ""
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    completed_at: float | None = None
    segments: list[SegmentState] = field(default_factory=list)

    @property
    def filepath(self) -> Path:
        return Path(self.save_dir) / self.filename

    @property
    def part_filepath(self) -> Path:
        return Path(self.save_dir) / f"{self.filename}.turbodm.part"

    @property
    def progress_fraction(self) -> float:
        if self.status == DownloadStatus.COMPLETED:
            return 1.0
        if self.total_size and self.total_size > 0:
            return min(1.0, max(0.0, self.downloaded_size / self.total_size))
        return 0.0

    @property
    def progress_percent(self) -> float:
        return self.progress_fraction * 100.0

    def to_summary_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "url": self.url,
            "filename": self.filename,
            "save_dir": self.save_dir,
            "category": self.category,
            "queue_id": self.queue_id,
            "status": self.status.value,
            "total_size": self.total_size,
            "downloaded_size": self.downloaded_size,
            "speed": self.speed,
            "eta": self.eta,
            "progress": round(self.progress_percent, 2),
            "resumable": self.resumable,
            "is_media": self.is_media,
            "error_message": self.error_message,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "completed_at": self.completed_at,
        }


@dataclass
class QueueConfig:
    """Download queue with concurrency and optional schedule."""

    id: str
    name: str
    max_concurrent: int = 3
    is_running: bool = False
    schedule_enabled: bool = False
    start_time: str = "02:00"  # HH:MM (24h)
    stop_time_enabled: bool = False
    stop_time: str = "06:00"  # HH:MM (24h)
    days_of_week: list[int] = field(default_factory=lambda: [0, 1, 2, 3, 4, 5, 6])  # 0=Mon..6=Sun
    on_complete_action: str = "notify"  # notify, command, suspend, shutdown
    on_complete_command: str = ""


@dataclass
class AppSettings:
    """Persistent user preferences for TurboDM."""

    default_connections: int = 16
    max_concurrent_downloads: int = 3
    global_speed_limit_enabled: bool = False
    global_speed_limit_kbps: int = 0  # 0 = unlimited
    max_retries: int = 5
    connect_timeout: float = 20.0
    read_timeout: float = 30.0
    user_agent: str = DEFAULT_USER_AGENT
    show_progress_dialog: bool = True
    keep_running_in_background: bool = True
    clipboard_monitor_enabled: bool = False
    autostart_enabled: bool = False
    notify_on_complete: bool = True
    proxy_enabled: bool = False
    proxy_url: str = ""
    ipc_port: int = IPC_DEFAULT_PORT
    downloads_root: str = field(default_factory=lambda: str(get_downloads_root()))
    category_folders: dict[str, str] = field(default_factory=dict)
    category_extensions: dict[str, list[str]] = field(default_factory=dict)
    verify_ssl: bool = True
