"""Central DownloadManager coordinating HTTP segmented downloads, yt-dlp media, queues, and persistence."""

from __future__ import annotations

import asyncio
import logging
import subprocess
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from turbodm.core.database import Database
from turbodm.core.engine import SegmentedDownloadTask, probe_url
from turbodm.core.models import (
    AppSettings,
    DownloadItem,
    DownloadStatus,
    ProbeResult,
    QueueConfig,
)
from turbodm.core.rate_limiter import TokenBucketRateLimiter
from turbodm.core.scheduler import QueueScheduler
from turbodm.media.ytdlp_engine import (
    YtDlpDownloadTask,
    is_streaming_url,
    probe_media_formats,
)
from turbodm.utils.formatting import (
    detect_category,
    filename_from_url,
    get_category_folder,
    sanitize_filename,
    unique_filepath,
)

logger = logging.getLogger(__name__)


class DownloadManager:
    """Thread-safe download controller with its own background asyncio event loop."""

    def __init__(self, db: Database | None = None) -> None:
        self.db = db or Database()
        self.settings: AppSettings = self.db.load_settings()
        self.queues: dict[str, QueueConfig] = {q.id: q for q in self.db.load_queues()}
        self._items: dict[str, DownloadItem] = {
            item.id: item for item in self.db.load_all_downloads()
        }
        self._lock = threading.RLock()

        # Active tasks: download_id -> (task_controller, asyncio.Task)
        self._running_controllers: dict[str, SegmentedDownloadTask | YtDlpDownloadTask] = {}
        self._running_futures: dict[str, asyncio.Task[DownloadStatus]] = {}

        # Global speed limiter
        initial_rate = (
            self.settings.global_speed_limit_kbps * 1024.0
            if self.settings.global_speed_limit_enabled and self.settings.global_speed_limit_kbps > 0
            else 0.0
        )
        self.global_limiter = TokenBucketRateLimiter(initial_rate)

        # Event loop thread
        self._loop: asyncio.AbstractEventLoop | None = None
        self._loop_thread: threading.Thread | None = None
        self._loop_ready = threading.Event()

        # Scheduler
        self.scheduler = QueueScheduler(self, on_queue_notify=self._emit_notification)

        # Listener callbacks
        self.on_item_added: list[Callable[[DownloadItem], None]] = []
        self.on_item_updated: list[Callable[[DownloadItem], None]] = []
        self.on_item_removed: list[Callable[[str], None]] = []
        self.on_item_completed: list[Callable[[DownloadItem], None]] = []
        self.on_queue_changed: list[Callable[[QueueConfig], None]] = []
        self.on_notification: list[Callable[[str, str], None]] = []

    # ---------- Lifecycle ----------

    def start(self) -> None:
        """Start background asyncio event loop thread and queue scheduler."""
        if self._loop_thread and self._loop_thread.is_alive():
            return
        self._loop_ready.clear()
        self._loop_thread = threading.Thread(
            target=self._run_loop_thread,
            name="turbodm-engine-loop",
            daemon=True,
        )
        self._loop_thread.start()
        self._loop_ready.wait(timeout=5.0)
        if self._loop:
            self._loop.call_soon_threadsafe(self.scheduler.start, self._loop)

    def _run_loop_thread(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self._loop = loop
        self._loop_ready.set()
        try:
            loop.run_forever()
        finally:
            pending = asyncio.all_tasks(loop)
            for t in pending:
                t.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()

    def shutdown(self) -> None:
        """Gracefully pause all running downloads, persist state, and stop event loop."""
        if self._loop and self._loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(self._async_shutdown(), self._loop)
            try:
                fut.result(timeout=8.0)
            except Exception:
                logger.exception("Error during manager async shutdown")
            self._loop.call_soon_threadsafe(self._loop.stop)
        if self._loop_thread and self._loop_thread.is_alive():
            self._loop_thread.join(timeout=3.0)
        self.db.close()

    async def _async_shutdown(self) -> None:
        await self.scheduler.stop()
        for ctrl in list(self._running_controllers.values()):
            ctrl.request_pause()
        tasks = list(self._running_futures.values())
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    @property
    def loop(self) -> asyncio.AbstractEventLoop | None:
        return self._loop

    # ---------- Queries ----------

    def list_downloads(self) -> list[DownloadItem]:
        with self._lock:
            return list(self._items.values())

    def get_download(self, download_id: str) -> DownloadItem | None:
        with self._lock:
            return self._items.get(download_id)

    def get_active_count(self) -> int:
        with self._lock:
            return sum(
                1
                for i in self._items.values()
                if i.status in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)
            )

    def get_total_speed(self) -> float:
        with self._lock:
            return sum(
                i.speed
                for i in self._items.values()
                if i.status == DownloadStatus.DOWNLOADING
            )

    def ensure_default_category_folders(self) -> None:
        """Create ~/Downloads/{Compressed,Documents,Music,Programs,Video} if missing."""
        root = Path(self.settings.downloads_root)
        for cat in ("Compressed", "Documents", "Music", "Programs", "Video", "General"):
            folder = get_category_folder(
                cat,
                custom_folders=self.settings.category_folders,
                downloads_root=root,
            )
            try:
                folder.mkdir(parents=True, exist_ok=True)
            except OSError:
                pass

    # ---------- Probing ----------

    def probe_async(
        self,
        url: str,
        referer: str = "",
        cookies: str = "",
        user_agent: str = "",
        on_done: Callable[[ProbeResult | None, Exception | None], None] | None = None,
    ) -> None:
        """Probe URL metadata (and yt-dlp formats if streaming URL) off the UI thread."""
        if not self._loop:
            return

        async def _do_probe() -> None:
            try:
                temp_item = DownloadItem(
                    url=url, referer=referer, cookies=cookies, user_agent=user_agent
                )
                if is_streaming_url(url):
                    media_info = await probe_media_formats(
                        url, self.settings, referer=referer, cookies=cookies, user_agent=user_agent
                    )
                    title = media_info["title"]
                    result = ProbeResult(
                        url=url,
                        final_url=url,
                        filename=f"{title}.mp4",
                        total_size=None,
                        accept_ranges=True,
                        content_type="video/mp4",
                        is_media_page=True,
                        media_title=title,
                        media_formats=media_info["formats"],
                    )
                else:
                    result = await probe_url(url, self.settings, temp_item)
                if on_done:
                    on_done(result, None)
            except Exception as exc:
                if on_done:
                    on_done(None, exc)

        asyncio.run_coroutine_threadsafe(_do_probe(), self._loop)

    # ---------- Add & Control Downloads ----------

    def add_download(
        self,
        url: str,
        filename: str = "",
        save_dir: str = "",
        category: str = "",
        queue_id: str = "main",
        description: str = "",
        referer: str = "",
        cookies: str = "",
        user_agent: str = "",
        headers: dict[str, str] | None = None,
        auth_user: str = "",
        auth_pass: str = "",
        max_connections: int | None = None,
        speed_limit_kbps: int = 0,
        is_media: bool | None = None,
        media_format_id: str = "",
        media_audio_only: bool = False,
        total_size: int | None = None,
        resumable: bool = False,
        final_url: str = "",
        etag: str = "",
        last_modified: str = "",
        checksum_expected: str = "",
        checksum_algo: str = "sha256",
        start_immediately: bool = True,
    ) -> DownloadItem:
        clean_name = sanitize_filename(filename) if filename else filename_from_url(url)
        detected_media = is_streaming_url(url) if is_media is None else is_media
        if detected_media and not Path(clean_name).suffix:
            clean_name = f"{clean_name}.mp3" if media_audio_only else f"{clean_name}.mp4"

        resolved_category = category or (
            "Music"
            if (detected_media and media_audio_only)
            else ("Video" if detected_media else detect_category(clean_name, self.settings.category_extensions))
        )
        if save_dir:
            target_dir = Path(save_dir)
        else:
            target_dir = get_category_folder(
                resolved_category,
                custom_folders=self.settings.category_folders,
                downloads_root=Path(self.settings.downloads_root),
            )
        target_dir.mkdir(parents=True, exist_ok=True)
        final_path = unique_filepath(target_dir, clean_name)

        item = DownloadItem(
            url=url.strip(),
            final_url=(final_url or url).strip(),
            filename=final_path.name,
            save_dir=str(final_path.parent),
            category=resolved_category,
            queue_id=queue_id if queue_id in self.queues else "main",
            status=DownloadStatus.QUEUED,
            total_size=total_size,
            resumable=resumable,
            max_connections=max_connections or self.settings.default_connections,
            speed_limit_kbps=speed_limit_kbps,
            description=description,
            referer=referer,
            cookies=cookies,
            user_agent=user_agent,
            headers=dict(headers or {}),
            auth_user=auth_user,
            auth_pass=auth_pass,
            etag=etag,
            last_modified=last_modified,
            is_media=detected_media,
            media_format_id=media_format_id,
            media_audio_only=media_audio_only,
            checksum_expected=checksum_expected,
            checksum_algo=checksum_algo,
        )

        with self._lock:
            self._items[item.id] = item
        self.db.save_download(item)
        self._emit(self.on_item_added, item)

        if start_immediately:
            self.start_download(item.id)
        return item

    def start_download(self, download_id: str) -> bool:
        with self._lock:
            item = self._items.get(download_id)
            if not item:
                return False
            if download_id in self._running_futures and not self._running_futures[download_id].done():
                return False
            if item.status == DownloadStatus.COMPLETED and item.filepath.exists():
                return False

        if not self._loop:
            return False

        asyncio.run_coroutine_threadsafe(self._launch_download(download_id), self._loop)
        return True

    async def _launch_download(self, download_id: str) -> None:
        with self._lock:
            item = self._items.get(download_id)
            if not item:
                return
            if download_id in self._running_futures and not self._running_futures[download_id].done():
                return

        if item.is_media:
            controller: SegmentedDownloadTask | YtDlpDownloadTask = YtDlpDownloadTask(
                item=item,
                settings=self.settings,
                on_progress=self._on_task_progress,
                on_persist=self.db.save_download,
            )
        else:
            controller = SegmentedDownloadTask(
                item=item,
                settings=self.settings,
                global_limiter=self.global_limiter,
                on_progress=self._on_task_progress,
                on_persist=self.db.save_download,
            )

        async def _runner() -> DownloadStatus:
            try:
                status = await controller.run()
                if status == DownloadStatus.COMPLETED:
                    self._handle_download_completed(item)
                return status
            finally:
                with self._lock:
                    self._running_controllers.pop(download_id, None)
                    self._running_futures.pop(download_id, None)
                # Let scheduler immediately advance the queue if running
                q = self.queues.get(item.queue_id)
                if q and q.is_running:
                    self.scheduler.pump_queue(q)

        task = asyncio.create_task(_runner())
        with self._lock:
            self._running_controllers[download_id] = controller
            self._running_futures[download_id] = task

    def pause_download(self, download_id: str) -> None:
        with self._lock:
            ctrl = self._running_controllers.get(download_id)
            item = self._items.get(download_id)
        if ctrl:
            ctrl.request_pause()
        elif item and item.status == DownloadStatus.QUEUED:
            item.status = DownloadStatus.PAUSED
            item.updated_at = time.time()
            self.db.save_download(item)
            self._emit(self.on_item_updated, item)

    def pause_all(self) -> None:
        with self._lock:
            ids = list(self._running_controllers.keys())
        for dl_id in ids:
            self.pause_download(dl_id)

    def cancel_download(self, download_id: str) -> None:
        with self._lock:
            ctrl = self._running_controllers.get(download_id)
            item = self._items.get(download_id)
        if ctrl:
            ctrl.request_cancel()
        elif item:
            item.status = DownloadStatus.CANCELLED
            item.speed = 0.0
            item.eta = None
            try:
                if item.part_filepath.exists():
                    item.part_filepath.unlink()
            except OSError:
                pass
            self.db.save_download(item)
            self._emit(self.on_item_updated, item)

    def delete_download(self, download_id: str, delete_file: bool = False) -> None:
        self.cancel_download(download_id)
        with self._lock:
            item = self._items.pop(download_id, None)
        if not item:
            return
        try:
            if item.part_filepath.exists():
                item.part_filepath.unlink()
            if delete_file and item.filepath.exists():
                item.filepath.unlink()
        except OSError:
            pass
        self.db.delete_download(download_id)
        self._emit(self.on_item_removed, download_id)

    def delete_completed(self) -> int:
        with self._lock:
            completed_ids = [
                i.id for i in self._items.values() if i.status == DownloadStatus.COMPLETED
            ]
        for dl_id in completed_ids:
            self.delete_download(dl_id, delete_file=False)
        return len(completed_ids)

    def redownload(self, download_id: str) -> None:
        with self._lock:
            item = self._items.get(download_id)
        if not item:
            return
        self.pause_download(download_id)
        item.segments = []
        item.downloaded_size = 0
        item.status = DownloadStatus.QUEUED
        item.error_message = ""
        item.completed_at = None
        item.checksum_verified = None
        try:
            if item.part_filepath.exists():
                item.part_filepath.unlink()
        except OSError:
            pass
        self.db.save_download(item)
        self._emit(self.on_item_updated, item)
        self.start_download(download_id)

    def set_download_speed_limit(self, download_id: str, kbps: int) -> None:
        with self._lock:
            item = self._items.get(download_id)
            ctrl = self._running_controllers.get(download_id)
        if item:
            item.speed_limit_kbps = max(0, int(kbps))
            self.db.save_download(item)
        if ctrl:
            ctrl.set_speed_limit_kbps(kbps)

    def set_global_speed_limit(self, enabled: bool, kbps: int | None = None) -> None:
        self.settings.global_speed_limit_enabled = enabled
        if kbps is not None:
            self.settings.global_speed_limit_kbps = max(0, int(kbps))
        effective = (
            self.settings.global_speed_limit_kbps
            if self.settings.global_speed_limit_enabled
            else 0
        )
        self.global_limiter.set_rate_kbps(effective)
        self.db.save_settings(self.settings)

    def update_settings(self, new_settings: AppSettings) -> None:
        self.settings = new_settings
        effective = (
            self.settings.global_speed_limit_kbps
            if self.settings.global_speed_limit_enabled
            else 0
        )
        self.global_limiter.set_rate_kbps(effective)
        self.db.save_settings(self.settings)

    # ---------- Queues ----------

    def start_queue(self, queue_id: str) -> None:
        q = self.queues.get(queue_id)
        if not q:
            return
        q.is_running = True
        self._notify_queue_changed(q)
        if self._loop:
            self._loop.call_soon_threadsafe(self.scheduler.pump_queue, q)

    def stop_queue(self, queue_id: str) -> None:
        q = self.queues.get(queue_id)
        if not q:
            return
        q.is_running = False
        with self._lock:
            to_pause = [
                i.id
                for i in self._items.values()
                if i.queue_id == queue_id
                and i.status in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)
            ]
        for dl_id in to_pause:
            self.pause_download(dl_id)
        self._notify_queue_changed(q)

    def save_queue(self, queue: QueueConfig) -> None:
        self.queues[queue.id] = queue
        self.db.save_queue(queue)
        self._notify_queue_changed(queue)

    def delete_queue(self, queue_id: str) -> None:
        if queue_id in ("main", "sync"):
            return
        self.stop_queue(queue_id)
        self.queues.pop(queue_id, None)
        with self._lock:
            for item in self._items.values():
                if item.queue_id == queue_id:
                    item.queue_id = "main"
        self.db.delete_queue(queue_id)
        for q in self.queues.values():
            self._notify_queue_changed(q)
            break

    def move_to_queue(self, download_id: str, queue_id: str) -> None:
        if queue_id not in self.queues:
            return
        with self._lock:
            item = self._items.get(download_id)
        if item:
            item.queue_id = queue_id
            self.db.save_download(item)
            self._emit(self.on_item_updated, item)

    # ---------- Internal callbacks ----------

    def _on_task_progress(self, item: DownloadItem) -> None:
        self._emit(self.on_item_updated, item)

    def _handle_download_completed(self, item: DownloadItem) -> None:
        self._emit(self.on_item_completed, item)
        if self.settings.notify_on_complete:
            self._emit_notification(
                "Download Complete",
                f"{item.filename} saved to {item.save_dir}",
            )
        action = item.on_complete_action
        if action == "open_file" and item.filepath.exists():
            try:
                subprocess.Popen(["xdg-open", str(item.filepath)])
            except OSError:
                pass
        elif action == "open_folder":
            try:
                subprocess.Popen(["xdg-open", str(item.save_dir)])
            except OSError:
                pass
        elif action == "command" and item.on_complete_command.strip():
            cmd = item.on_complete_command.replace("{file}", str(item.filepath)).replace(
                "{dir}", str(item.save_dir)
            )
            try:
                subprocess.Popen(cmd, shell=True)
            except OSError:
                pass

    def _notify_queue_changed(self, queue: QueueConfig) -> None:
        self._emit(self.on_queue_changed, queue)

    def _emit_notification(self, title: str, body: str) -> None:
        for cb in list(self.on_notification):
            try:
                cb(title, body)
            except Exception:
                logger.exception("Notification callback error")

    @staticmethod
    def _emit(callbacks: list[Callable[..., Any]], *args: Any) -> None:
        for cb in list(callbacks):
            try:
                cb(*args)
            except Exception:
                logger.exception("Event callback error")
