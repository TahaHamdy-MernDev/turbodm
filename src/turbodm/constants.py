"""Application constants, XDG paths, and default IDM-style category mappings."""

from __future__ import annotations

import os
from pathlib import Path

APP_NAME = "TurboDM"
APP_ID = "com.turbodm.app"
APP_VERSION = "1.0.0"
NATIVE_HOST_NAME = "com.turbodm.host"

IPC_HOST = "127.0.0.1"
IPC_DEFAULT_PORT = 17492

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 TurboDM/1.0"
)


def get_config_dir() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    path = base / "turbodm"
    path.mkdir(parents=True, exist_ok=True)
    return path


def get_data_dir() -> Path:
    base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    path = base / "turbodm"
    path.mkdir(parents=True, exist_ok=True)
    return path


def get_cache_dir() -> Path:
    base = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
    path = base / "turbodm"
    path.mkdir(parents=True, exist_ok=True)
    return path


def get_downloads_root() -> Path:
    xdg_dl = os.environ.get("XDG_DOWNLOAD_DIR")
    if xdg_dl:
        return Path(xdg_dl)
    return Path.home() / "Downloads"


DEFAULT_CATEGORIES: dict[str, dict[str, object]] = {
    "Compressed": {
        "extensions": [
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst",
            "lz", "lz4", "cab", "iso", "img",
        ],
        "folder": "Compressed",
        "icon": "package-x-generic-symbolic",
    },
    "Documents": {
        "extensions": [
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt",
            "ods", "odp", "txt", "rtf", "csv", "epub", "mobi", "md", "djvu",
        ],
        "folder": "Documents",
        "icon": "x-office-document-symbolic",
    },
    "Music": {
        "extensions": [
            "mp3", "flac", "wav", "aac", "ogg", "opus", "m4a", "wma",
            "aiff", "alac", "mid",
        ],
        "folder": "Music",
        "icon": "audio-x-generic-symbolic",
    },
    "Programs": {
        "extensions": [
            "rpm", "deb", "appimage", "flatpak", "flatpakref", "sh", "bin",
            "run", "exe", "msi", "dmg", "pkg", "apk", "jar", "whl",
        ],
        "folder": "Programs",
        "icon": "application-x-executable-symbolic",
    },
    "Video": {
        "extensions": [
            "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v",
            "mpg", "mpeg", "3gp", "ts", "m3u8", "mpd",
        ],
        "folder": "Video",
        "icon": "video-x-generic-symbolic",
    },
    "General": {
        "extensions": [],
        "folder": "",
        "icon": "folder-download-symbolic",
    },
}

DEFAULT_QUEUES = [
    {"id": "main", "name": "Main Download Queue", "max_concurrent": 3},
    {"id": "sync", "name": "Synchronization Queue", "max_concurrent": 2},
]

# Dynamic segmentation & high-throughput I/O tuning (matching IDM turbo behavior)
MIN_SEGMENT_SIZE_BYTES = 256 * 1024       # 256 KB minimum chunk for splitting
DYNAMIC_SPLIT_THRESHOLD_BYTES = 256 * 1024  # Split remaining >= 256 KB whenever a worker slot is free
CHUNK_READ_SIZE = 256 * 1024              # 256 KB network read buffer
WRITE_BUFFER_SIZE = 1024 * 1024           # 1 MB per-segment write buffer before os.pwrite flush
