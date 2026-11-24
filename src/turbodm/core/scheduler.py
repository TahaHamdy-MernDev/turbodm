"""Queue runner and time-based download scheduler (IDM Scheduler equivalent)."""

from __future__ import annotations

import asyncio
import datetime
import logging
import subprocess
from collections.abc import Callable
from typing import TYPE_CHECKING

from turbodm.core.models import DownloadStatus, QueueConfig

if TYPE_CHECKING:
    from turbodm.core.manager import DownloadManager

logger = logging.getLogger(__name__)


class QueueScheduler:
    """Periodically checks scheduled queues and advances queued downloads up to max_concurrent."""

    def __init__(
        self,
        manager: DownloadManager,
        on_queue_notify: Callable[[str, str], None] | None = None,
    ) -> None:
        self.manager = manager
        self.on_queue_notify = on_queue_notify
        self._task: asyncio.Task[None] | None = None
        self._running = False
        self._last_triggered_minute: dict[str, str] = {}

    def start(self, loop: asyncio.AbstractEventLoop) -> None:
        if self._running:
            return
        self._running = True
        self._task = loop.create_task(self._tick_loop())

    async def stop(self) -> None:
        self._running = False
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        self._task = None

    async def _tick_loop(self) -> None:
        while self._running:
            try:
                self.check_schedules()
                self.pump_all_running_queues()
            except Exception:
                logger.exception("Scheduler tick error")
            await asyncio.sleep(2.0)

    def check_schedules(self, now: datetime.datetime | None = None) -> None:
        """Start or stop queues whose scheduled start/stop HH:MM matches current time."""
        current = now or datetime.datetime.now()
        hhmm = current.strftime("%H:%M")
        minute_key = current.strftime("%Y-%m-%d %H:%M")
        weekday = current.weekday()

        for q in list(self.manager.queues.values()):
            if not q.schedule_enabled:
                continue
            if weekday not in q.days_of_week:
                continue

            start_token = f"{q.id}:start:{minute_key}"
            if hhmm == q.start_time and not q.is_running:
                if self._last_triggered_minute.get(q.id) != start_token:
                    self._last_triggered_minute[q.id] = start_token
                    logger.info("Scheduler starting queue '%s' at %s", q.name, hhmm)
                    self.manager.start_queue(q.id)

            stop_token = f"{q.id}:stop:{minute_key}"
            if q.stop_time_enabled and hhmm == q.stop_time and q.is_running:
                if self._last_triggered_minute.get(q.id) != stop_token:
                    self._last_triggered_minute[q.id] = stop_token
                    logger.info("Scheduler stopping queue '%s' at %s", q.name, hhmm)
                    self.manager.stop_queue(q.id)

    def pump_all_running_queues(self) -> None:
        for q in list(self.manager.queues.values()):
            if q.is_running:
                self.pump_queue(q)

    def pump_queue(self, queue: QueueConfig) -> None:
        """Start queued/paused items in `queue` up to `queue.max_concurrent`."""
        items = [
            item
            for item in self.manager.list_downloads()
            if item.queue_id == queue.id
        ]
        # Process oldest first (FIFO order like IDM)
        items.sort(key=lambda x: x.created_at)

        active = [
            i
            for i in items
            if i.status in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)
        ]
        waiting = [
            i
            for i in items
            if i.status in (DownloadStatus.QUEUED, DownloadStatus.PAUSED)
        ]

        slots = max(0, queue.max_concurrent - len(active))
        for item in waiting[:slots]:
            self.manager.start_download(item.id)

        # If nothing is active and nothing is waiting, queue finished!
        if not active and not waiting and queue.is_running:
            queue.is_running = False
            self.manager._notify_queue_changed(queue)
            self._run_queue_complete_action(queue)

    def _run_queue_complete_action(self, queue: QueueConfig) -> None:
        action = queue.on_complete_action
        if self.on_queue_notify:
            self.on_queue_notify(
                f"Queue Completed: {queue.name}",
                f"All downloads in '{queue.name}' have finished.",
            )
        if action == "command" and queue.on_complete_command.strip():
            try:
                subprocess.Popen(queue.on_complete_command, shell=True)
            except OSError:
                logger.exception("Failed to run queue post-action command")
        elif action == "suspend":
            try:
                subprocess.Popen(["systemctl", "suspend"])
            except OSError:
                logger.exception("Failed to suspend system")
        elif action == "shutdown":
            try:
                subprocess.Popen(["systemctl", "poweroff"])
            except OSError:
                logger.exception("Failed to power off system")
