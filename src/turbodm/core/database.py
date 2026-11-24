"""Thread-safe SQLite persistence for downloads, segments, queues, and settings."""

from __future__ import annotations

import json
import sqlite3
import threading
from dataclasses import asdict
from pathlib import Path

from turbodm.constants import DEFAULT_QUEUES, get_data_dir
from turbodm.core.models import (
    AppSettings,
    DownloadItem,
    DownloadStatus,
    QueueConfig,
    SegmentState,
)


class Database:
    """SQLite storage backend with WAL mode for safe concurrent reads/writes."""

    def __init__(self, db_path: Path | None = None) -> None:
        self.db_path = db_path or (get_data_dir() / "turbodm.db")
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=NORMAL")
        self._init_schema()

    def _init_schema(self) -> None:
        with self._lock:
            self._conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS downloads (
                    id TEXT PRIMARY KEY,
                    url TEXT NOT NULL,
                    final_url TEXT DEFAULT '',
                    filename TEXT NOT NULL,
                    save_dir TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'General',
                    queue_id TEXT NOT NULL DEFAULT 'main',
                    status TEXT NOT NULL DEFAULT 'queued',
                    total_size INTEGER,
                    downloaded_size INTEGER NOT NULL DEFAULT 0,
                    resumable INTEGER NOT NULL DEFAULT 0,
                    max_connections INTEGER NOT NULL DEFAULT 8,
                    speed_limit_kbps INTEGER NOT NULL DEFAULT 0,
                    description TEXT DEFAULT '',
                    referer TEXT DEFAULT '',
                    cookies TEXT DEFAULT '',
                    user_agent TEXT DEFAULT '',
                    headers_json TEXT DEFAULT '{}',
                    auth_user TEXT DEFAULT '',
                    auth_pass TEXT DEFAULT '',
                    etag TEXT DEFAULT '',
                    last_modified TEXT DEFAULT '',
                    mime_type TEXT DEFAULT '',
                    error_message TEXT DEFAULT '',
                    is_media INTEGER NOT NULL DEFAULT 0,
                    media_format_id TEXT DEFAULT '',
                    media_audio_only INTEGER NOT NULL DEFAULT 0,
                    checksum_expected TEXT DEFAULT '',
                    checksum_algo TEXT DEFAULT 'sha256',
                    checksum_verified INTEGER,
                    on_complete_action TEXT DEFAULT 'notify',
                    on_complete_command TEXT DEFAULT '',
                    segments_json TEXT DEFAULT '[]',
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    completed_at REAL
                );

                CREATE TABLE IF NOT EXISTS queues (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    max_concurrent INTEGER NOT NULL DEFAULT 3,
                    schedule_enabled INTEGER NOT NULL DEFAULT 0,
                    start_time TEXT NOT NULL DEFAULT '02:00',
                    stop_time_enabled INTEGER NOT NULL DEFAULT 0,
                    stop_time TEXT NOT NULL DEFAULT '06:00',
                    days_json TEXT NOT NULL DEFAULT '[0,1,2,3,4,5,6]',
                    on_complete_action TEXT NOT NULL DEFAULT 'notify',
                    on_complete_command TEXT NOT NULL DEFAULT ''
                );

                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value_json TEXT NOT NULL
                );
                """
            )
            # Ensure default queues exist
            for q in DEFAULT_QUEUES:
                self._conn.execute(
                    """
                    INSERT OR IGNORE INTO queues (id, name, max_concurrent)
                    VALUES (?, ?, ?)
                    """,
                    (q["id"], q["name"], q["max_concurrent"]),
                )
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ---------- Downloads ----------

    def save_download(self, item: DownloadItem) -> None:
        segments_json = json.dumps([s.to_dict() for s in item.segments])
        headers_json = json.dumps(item.headers)
        verified_val = (
            None
            if item.checksum_verified is None
            else (1 if item.checksum_verified else 0)
        )
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO downloads (
                    id, url, final_url, filename, save_dir, category, queue_id,
                    status, total_size, downloaded_size, resumable, max_connections,
                    speed_limit_kbps, description, referer, cookies, user_agent,
                    headers_json, auth_user, auth_pass, etag, last_modified,
                    mime_type, error_message, is_media, media_format_id,
                    media_audio_only, checksum_expected, checksum_algo,
                    checksum_verified, on_complete_action, on_complete_command,
                    segments_json, created_at, updated_at, completed_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT(id) DO UPDATE SET
                    url=excluded.url,
                    final_url=excluded.final_url,
                    filename=excluded.filename,
                    save_dir=excluded.save_dir,
                    category=excluded.category,
                    queue_id=excluded.queue_id,
                    status=excluded.status,
                    total_size=excluded.total_size,
                    downloaded_size=excluded.downloaded_size,
                    resumable=excluded.resumable,
                    max_connections=excluded.max_connections,
                    speed_limit_kbps=excluded.speed_limit_kbps,
                    description=excluded.description,
                    referer=excluded.referer,
                    cookies=excluded.cookies,
                    user_agent=excluded.user_agent,
                    headers_json=excluded.headers_json,
                    auth_user=excluded.auth_user,
                    auth_pass=excluded.auth_pass,
                    etag=excluded.etag,
                    last_modified=excluded.last_modified,
                    mime_type=excluded.mime_type,
                    error_message=excluded.error_message,
                    is_media=excluded.is_media,
                    media_format_id=excluded.media_format_id,
                    media_audio_only=excluded.media_audio_only,
                    checksum_expected=excluded.checksum_expected,
                    checksum_algo=excluded.checksum_algo,
                    checksum_verified=excluded.checksum_verified,
                    on_complete_action=excluded.on_complete_action,
                    on_complete_command=excluded.on_complete_command,
                    segments_json=excluded.segments_json,
                    updated_at=excluded.updated_at,
                    completed_at=excluded.completed_at
                """,
                (
                    item.id,
                    item.url,
                    item.final_url,
                    item.filename,
                    item.save_dir,
                    item.category,
                    item.queue_id,
                    item.status.value,
                    item.total_size,
                    item.downloaded_size,
                    1 if item.resumable else 0,
                    item.max_connections,
                    item.speed_limit_kbps,
                    item.description,
                    item.referer,
                    item.cookies,
                    item.user_agent,
                    headers_json,
                    item.auth_user,
                    item.auth_pass,
                    item.etag,
                    item.last_modified,
                    item.mime_type,
                    item.error_message,
                    1 if item.is_media else 0,
                    item.media_format_id,
                    1 if item.media_audio_only else 0,
                    item.checksum_expected,
                    item.checksum_algo,
                    verified_val,
                    item.on_complete_action,
                    item.on_complete_command,
                    segments_json,
                    item.created_at,
                    item.updated_at,
                    item.completed_at,
                ),
            )
            self._conn.commit()

    def load_all_downloads(self) -> list[DownloadItem]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM downloads ORDER BY created_at DESC"
            ).fetchall()
        items: list[DownloadItem] = []
        for r in rows:
            status = DownloadStatus(r["status"])
            # Any download that was mid-flight when app crashed/closed becomes PAUSED so user can resume
            if status in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING):
                status = DownloadStatus.PAUSED
            segs_raw = json.loads(r["segments_json"] or "[]")
            headers = json.loads(r["headers_json"] or "{}")
            verified_raw = r["checksum_verified"]
            item = DownloadItem(
                id=r["id"],
                url=r["url"],
                final_url=r["final_url"] or "",
                filename=r["filename"],
                save_dir=r["save_dir"],
                category=r["category"],
                queue_id=r["queue_id"],
                status=status,
                total_size=r["total_size"],
                downloaded_size=r["downloaded_size"],
                resumable=bool(r["resumable"]),
                max_connections=r["max_connections"],
                speed_limit_kbps=r["speed_limit_kbps"],
                description=r["description"] or "",
                referer=r["referer"] or "",
                cookies=r["cookies"] or "",
                user_agent=r["user_agent"] or "",
                headers=headers,
                auth_user=r["auth_user"] or "",
                auth_pass=r["auth_pass"] or "",
                etag=r["etag"] or "",
                last_modified=r["last_modified"] or "",
                mime_type=r["mime_type"] or "",
                error_message=r["error_message"] or "",
                is_media=bool(r["is_media"]),
                media_format_id=r["media_format_id"] or "",
                media_audio_only=bool(r["media_audio_only"]),
                checksum_expected=r["checksum_expected"] or "",
                checksum_algo=r["checksum_algo"] or "sha256",
                checksum_verified=None if verified_raw is None else bool(verified_raw),
                on_complete_action=r["on_complete_action"] or "notify",
                on_complete_command=r["on_complete_command"] or "",
                created_at=r["created_at"],
                updated_at=r["updated_at"],
                completed_at=r["completed_at"],
                segments=[SegmentState.from_dict(s) for s in segs_raw],
            )
            items.append(item)
        return items

    def delete_download(self, download_id: str) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM downloads WHERE id = ?", (download_id,))
            self._conn.commit()

    # ---------- Queues ----------

    def load_queues(self) -> list[QueueConfig]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM queues ORDER BY rowid ASC").fetchall()
        result: list[QueueConfig] = []
        for r in rows:
            result.append(
                QueueConfig(
                    id=r["id"],
                    name=r["name"],
                    max_concurrent=r["max_concurrent"],
                    schedule_enabled=bool(r["schedule_enabled"]),
                    start_time=r["start_time"],
                    stop_time_enabled=bool(r["stop_time_enabled"]),
                    stop_time=r["stop_time"],
                    days_of_week=json.loads(r["days_json"] or "[0,1,2,3,4,5,6]"),
                    on_complete_action=r["on_complete_action"],
                    on_complete_command=r["on_complete_command"] or "",
                )
            )
        return result

    def save_queue(self, queue: QueueConfig) -> None:
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO queues (
                    id, name, max_concurrent, schedule_enabled, start_time,
                    stop_time_enabled, stop_time, days_json,
                    on_complete_action, on_complete_command
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name=excluded.name,
                    max_concurrent=excluded.max_concurrent,
                    schedule_enabled=excluded.schedule_enabled,
                    start_time=excluded.start_time,
                    stop_time_enabled=excluded.stop_time_enabled,
                    stop_time=excluded.stop_time,
                    days_json=excluded.days_json,
                    on_complete_action=excluded.on_complete_action,
                    on_complete_command=excluded.on_complete_command
                """,
                (
                    queue.id,
                    queue.name,
                    queue.max_concurrent,
                    1 if queue.schedule_enabled else 0,
                    queue.start_time,
                    1 if queue.stop_time_enabled else 0,
                    queue.stop_time,
                    json.dumps(queue.days_of_week),
                    queue.on_complete_action,
                    queue.on_complete_command,
                ),
            )
            self._conn.commit()

    def delete_queue(self, queue_id: str) -> None:
        if queue_id in ("main", "sync"):
            return
        with self._lock:
            self._conn.execute(
                "UPDATE downloads SET queue_id = 'main' WHERE queue_id = ?", (queue_id,)
            )
            self._conn.execute("DELETE FROM queues WHERE id = ?", (queue_id,))
            self._conn.commit()

    # ---------- Settings ----------

    def load_settings(self) -> AppSettings:
        settings = AppSettings()
        with self._lock:
            row = self._conn.execute(
                "SELECT value_json FROM settings WHERE key = 'app_settings'"
            ).fetchone()
        if not row:
            return settings
        try:
            data = json.loads(row["value_json"])
            for field_name in asdict(settings):
                if field_name in data:
                    setattr(settings, field_name, data[field_name])
        except (ValueError, TypeError):
            pass
        return settings

    def save_settings(self, settings: AppSettings) -> None:
        payload = json.dumps(asdict(settings))
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO settings (key, value_json) VALUES ('app_settings', ?)
                ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json
                """,
                (payload,),
            )
            self._conn.commit()
