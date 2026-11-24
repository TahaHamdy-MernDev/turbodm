"""Tests for SQLite Database persistence, crash recovery, queues, and settings."""

from __future__ import annotations

from pathlib import Path

from turbodm.core.database import Database
from turbodm.core.models import (
    AppSettings,
    DownloadItem,
    DownloadStatus,
    QueueConfig,
    SegmentState,
    SegmentStatus,
)


def test_database_roundtrip_and_crash_recovery(tmp_path: Path):
    db_file = tmp_path / "test.db"
    db = Database(db_file)

    item = DownloadItem(
        id="dl_001",
        url="https://example.com/fedora.iso",
        filename="fedora.iso",
        save_dir=str(tmp_path),
        category="Compressed",
        status=DownloadStatus.DOWNLOADING,
        total_size=1024 * 1024,
        downloaded_size=256 * 1024,
        resumable=True,
        segments=[
            SegmentState(index=0, start=0, end=524287, downloaded=256 * 1024, status=SegmentStatus.RECEIVING),
            SegmentState(index=1, start=524288, end=1048575, downloaded=0, status=SegmentStatus.PENDING),
        ],
    )
    db.save_download(item)
    db.close()

    # Reopen DB: mid-flight DOWNLOADING must recover safely as PAUSED with exact segment offsets
    db2 = Database(db_file)
    loaded = db2.load_all_downloads()
    assert len(loaded) == 1
    rec = loaded[0]
    assert rec.id == "dl_001"
    assert rec.status == DownloadStatus.PAUSED
    assert len(rec.segments) == 2
    assert rec.segments[0].downloaded == 256 * 1024
    assert rec.segments[1].start == 524288

    # Settings persistence
    s = AppSettings(default_connections=16, global_speed_limit_enabled=True, global_speed_limit_kbps=2048)
    db2.save_settings(s)
    s2 = db2.load_settings()
    assert s2.default_connections == 16
    assert s2.global_speed_limit_enabled is True
    assert s2.global_speed_limit_kbps == 2048

    # Custom queue persistence & deletion reassigning downloads back to 'main'
    q = QueueConfig(id="night", name="Night Queue", max_concurrent=4, schedule_enabled=True, start_time="03:00")
    db2.save_queue(q)
    rec.queue_id = "night"
    db2.save_download(rec)

    queues = {x.id: x for x in db2.load_queues()}
    assert "night" in queues
    assert queues["night"].start_time == "03:00"

    db2.delete_queue("night")
    loaded_after = db2.load_all_downloads()
    assert loaded_after[0].queue_id == "main"
    db2.close()
