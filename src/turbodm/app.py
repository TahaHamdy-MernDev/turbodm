"""TurboDM GTK4 / Libadwaita Adw.Application entry point."""

from __future__ import annotations

import asyncio
import logging
import sys
from pathlib import Path
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from turbodm.constants import APP_ID, APP_NAME, APP_VERSION  # noqa: E402
from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.ipc.server import LocalIPCServer  # noqa: E402
from turbodm.ui.main_window import MainWindow  # noqa: E402

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


class TurboDMApplication(Adw.Application):
    """Single-instance GNOME/Wayland application with background daemon hold & IPC server."""

    def __init__(self) -> None:
        super().__init__(
            application_id=APP_ID,
            flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE,
        )
        self.manager: DownloadManager | None = None
        self.ipc_server: LocalIPCServer | None = None
        self.main_window: MainWindow | None = None
        self._holding = False
        self._css_loaded = False

        self.add_main_option(
            "background",
            ord("b"),
            GLib.OptionFlags.NONE,
            GLib.OptionArg.NONE,
            "Start silently in background (for GNOME autostart / browser bridge)",
            None,
        )

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)
        self._load_css()
        self._register_actions()

        self.manager = DownloadManager()
        self.manager.ensure_default_category_folders()
        self.manager.on_notification.append(self._send_desktop_notification)
        self.manager.start()

        # Start Local IPC Server on the manager's asyncio loop
        self.ipc_server = LocalIPCServer(
            manager=self.manager,
            port=self.manager.settings.ipc_port,
            on_incoming_download=self._on_ipc_download,
            on_incoming_batch=self._on_ipc_batch,
            on_focus_window=self._on_ipc_focus,
        )
        if self.manager.loop:
            asyncio.run_coroutine_threadsafe(self.ipc_server.start(), self.manager.loop)

        # Keep application alive even when the main window is hidden
        if not self._holding:
            self.hold()
            self._holding = True

    def do_activate(self) -> None:
        self._ensure_main_window()
        assert self.main_window is not None
        self.main_window.set_visible(True)
        self.main_window.present()

    def do_command_line(self, command_line: Gio.ApplicationCommandLine) -> int:
        options = command_line.get_options_dict()
        background = options.contains("background")
        args = command_line.get_arguments()[1:]
        urls = [a for a in args if a.startswith(("http://", "https://", "ftp://", "turbodm://"))]

        self._ensure_main_window()
        assert self.main_window is not None

        if not background:
            self.main_window.set_visible(True)
            self.main_window.present()

        for u in urls:
            clean_url = u[len("turbodm://") :] if u.startswith("turbodm://") else u
            self.main_window.open_add_dialog(initial_url=clean_url)

        return 0

    def do_shutdown(self) -> None:
        if self.ipc_server and self.manager and self.manager.loop and self.manager.loop.is_running():
            fut = asyncio.run_coroutine_threadsafe(self.ipc_server.stop(), self.manager.loop)
            try:
                fut.result(timeout=3.0)
            except Exception:
                pass
        if self.manager:
            self.manager.shutdown()
        Adw.Application.do_shutdown(self)

    def _ensure_main_window(self) -> None:
        if self.main_window is None and self.manager is not None:
            self.main_window = MainWindow(self, self.manager)

    def _load_css(self) -> None:
        if self._css_loaded:
            return
        css_path = Path(__file__).parent / "ui" / "style.css"
        if css_path.exists():
            provider = Gtk.CssProvider()
            provider.load_from_path(str(css_path))
            display = Gdk.Display.get_default()
            if display:
                Gtk.StyleContext.add_provider_for_display(
                    display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
                )
        self._css_loaded = True

    def _register_actions(self) -> None:
        def _act(name: str, cb: Any, accel: str | None = None) -> None:
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", lambda *_: cb())
            self.add_action(action)
            if accel:
                self.set_accels_for_action(f"app.{name}", [accel])

        _act("activate", self.do_activate)
        _act("add_url", lambda: (self.do_activate(), self.main_window and self.main_window.open_add_dialog()), "<Primary>n")
        _act("find", lambda: self.main_window and self.main_window.search_toggle.set_active(True), "<Primary>f")
        _act("batch", lambda: (self.do_activate(), self.main_window and self.main_window.open_batch_dialog()))
        _act("scheduler", lambda: (self.do_activate(), self.main_window and self.main_window.open_scheduler_dialog()))
        _act("settings", lambda: (self.do_activate(), self.main_window and self.main_window.open_settings_dialog()), "<Primary>comma")
        _act("about", self._show_about)
        _act("quit", self._force_quit, "<Primary>q")

    def _show_about(self) -> None:
        self._ensure_main_window()
        about = Adw.AboutDialog(
            application_name=APP_NAME,
            application_icon=APP_ID,
            version=APP_VERSION,
            developer_name="TurboDM Project",
            comments=(
                "Native Linux high-speed multi-part download manager for Fedora GNOME.\n"
                "Features dynamic segmentation, pause/resume, queues & scheduler, "
                "yt-dlp + ffmpeg video grabbing, and Chrome/Firefox browser integration."
            ),
            license_type=Gtk.License.MIT_X11,
        )
        about.present(self.main_window)

    def _force_quit(self) -> None:
        if self._holding:
            self.release()
            self._holding = False
        self.quit()

    def _send_desktop_notification(self, title: str, body: str) -> None:
        def _notify() -> bool:
            notif = Gio.Notification.new(title)
            notif.set_body(body)
            notif.set_default_action("app.activate")
            try:
                self.send_notification(None, notif)
            except Exception:
                pass
            return False

        GLib.idle_add(_notify)

    # ---------- IPC Bridge Callbacks (called from asyncio thread) ----------

    def _on_ipc_download(self, payload: dict[str, Any]) -> None:
        def _open() -> bool:
            self._ensure_main_window()
            assert self.main_window is not None
            self.main_window.open_add_dialog(
                initial_url=str(payload.get("url", "")),
                initial_filename=str(payload.get("filename", "")),
                referer=str(payload.get("referer", "")),
                cookies=str(payload.get("cookies", "")),
                user_agent=str(payload.get("user_agent", "")),
                headers=payload.get("headers") if isinstance(payload.get("headers"), dict) else None,
                is_media_hint=payload.get("is_media"),
            )
            return False

        GLib.idle_add(_open)

    def _on_ipc_batch(self, urls: list[str], payload: dict[str, Any]) -> None:
        def _open() -> bool:
            self._ensure_main_window()
            assert self.main_window is not None
            self.main_window.set_visible(True)
            self.main_window.present()
            self.main_window.open_batch_dialog(
                initial_urls=urls,
                referer=str(payload.get("referer", "")),
                cookies=str(payload.get("cookies", "")),
                user_agent=str(payload.get("user_agent", "")),
            )
            return False

        GLib.idle_add(_open)

    def _on_ipc_focus(self) -> None:
        GLib.idle_add(self.do_activate)


def main() -> int:
    app = TurboDMApplication()
    return app.run(sys.argv)


if __name__ == "__main__":
    sys.exit(main())
