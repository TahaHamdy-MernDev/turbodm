"""IDM-style Options / Preferences dialog built with Libadwaita Adw.PreferencesDialog."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from turbodm.constants import APP_ID, DEFAULT_CATEGORIES, IPC_HOST  # noqa: E402
from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.ipc.server import get_or_create_ipc_token  # noqa: E402
from turbodm.utils.formatting import get_category_folder  # noqa: E402


def sync_gnome_autostart(enabled: bool) -> None:
    """Create or remove ~/.config/autostart/com.turbodm.app.desktop for GNOME login."""
    autostart_dir = Path.home() / ".config" / "autostart"
    desktop_file = autostart_dir / f"{APP_ID}.desktop"
    if enabled:
        autostart_dir.mkdir(parents=True, exist_ok=True)
        exec_path = Path.home() / ".local" / "bin" / "turbodm"
        exec_cmd = f"{exec_path} --background" if exec_path.exists() else "turbodm --background"
        desktop_file.write_text(
            "[Desktop Entry]\n"
            "Type=Application\n"
            "Name=TurboDM\n"
            "Comment=High-speed multi-part download manager for Fedora\n"
            f"Exec={exec_cmd}\n"
            f"Icon={APP_ID}\n"
            "Terminal=false\n"
            "Categories=Network;FileTransfer;\n"
            "X-GNOME-Autostart-enabled=true\n",
            encoding="utf-8",
        )
    else:
        if desktop_file.exists():
            try:
                desktop_file.unlink()
            except OSError:
                pass


class SettingsDialog(Adw.PreferencesDialog):
    """Multi-page Preferences dialog mirroring IDM Options."""

    def __init__(
        self,
        manager: DownloadManager,
        on_saved: Callable[[], None] | None = None,
    ) -> None:
        super().__init__(title="TurboDM Options")
        self.manager = manager
        self._on_saved = on_saved
        self._cat_entries: dict[str, Adw.EntryRow] = {}

        self._build_general_page()
        self._build_connections_page()
        self._build_save_to_page()
        self._build_proxy_page()
        self._build_browser_page()

        self.connect("closed", self._on_closed)

    def _build_general_page(self) -> None:
        s = self.manager.settings
        page = Adw.PreferencesPage(title="General", icon_name="preferences-system-symbolic")

        grp_ui = Adw.PreferencesGroup(title="Window &amp; Background Behavior")
        self.sw_progress = Adw.SwitchRow(
            title="Show Download Progress Dialog",
            subtitle="Open a dedicated IDM-style progress window with segment bars when a download starts",
            active=s.show_progress_dialog,
        )
        grp_ui.add(self.sw_progress)

        self.sw_background = Adw.SwitchRow(
            title="Keep Running in Background on Close",
            subtitle="Closing the main window hides it so active downloads and browser integration continue",
            active=s.keep_running_in_background,
        )
        grp_ui.add(self.sw_background)

        self.sw_notify = Adw.SwitchRow(
            title="Desktop Notifications",
            subtitle="Show GNOME notifications when downloads or queues finish",
            active=s.notify_on_complete,
        )
        grp_ui.add(self.sw_notify)
        page.add(grp_ui)

        grp_auto = Adw.PreferencesGroup(title="Automation")
        self.sw_clipboard = Adw.SwitchRow(
            title="Monitor Clipboard for Download URLs",
            subtitle="Prompt with 'Download File Info' when a direct file URL is copied",
            active=s.clipboard_monitor_enabled,
        )
        grp_auto.add(self.sw_clipboard)

        self.sw_autostart = Adw.SwitchRow(
            title="Launch TurboDM on GNOME Login",
            subtitle="Start silently in background at session startup",
            active=s.autostart_enabled,
        )
        grp_auto.add(self.sw_autostart)
        page.add(grp_auto)

        self.add(page)

    def _build_connections_page(self) -> None:
        s = self.manager.settings
        page = Adw.PreferencesPage(title="Connections", icon_name="network-transmit-receive-symbolic")

        grp_eng = Adw.PreferencesGroup(
            title="Segmented Download Engine",
            description="Controls parallel byte-range connections and dynamic segmentation.",
        )
        self.spin_conns = Adw.SpinRow.new_with_range(1, 32, 1)
        self.spin_conns.set_title("Default Max Connections")
        self.spin_conns.set_subtitle("Number of parallel parts per file (recommended: 8–16)")
        self.spin_conns.set_value(s.default_connections)
        grp_eng.add(self.spin_conns)

        self.spin_retries = Adw.SpinRow.new_with_range(0, 20, 1)
        self.spin_retries.set_title("Retry Attempts per Segment")
        self.spin_retries.set_value(s.max_retries)
        grp_eng.add(self.spin_retries)

        self.spin_timeout = Adw.SpinRow.new_with_range(5, 120, 5)
        self.spin_timeout.set_title("Connection Timeout (seconds)")
        self.spin_timeout.set_value(s.connect_timeout)
        grp_eng.add(self.spin_timeout)

        self.entry_ua = Adw.EntryRow(title="Default User-Agent", text=s.user_agent)
        grp_eng.add(self.entry_ua)

        self.sw_ssl = Adw.SwitchRow(
            title="Verify HTTPS SSL Certificates",
            active=s.verify_ssl,
        )
        grp_eng.add(self.sw_ssl)
        page.add(grp_eng)

        grp_lim = Adw.PreferencesGroup(title="Global Speed Limiter")
        self.sw_global_limit = Adw.SwitchRow(
            title="Enable Global Speed Limit",
            subtitle="Caps total bandwidth across all active downloads",
            active=s.global_speed_limit_enabled,
        )
        grp_lim.add(self.sw_global_limit)

        self.spin_global_kbps = Adw.SpinRow.new_with_range(0, 500000, 64)
        self.spin_global_kbps.set_title("Maximum Speed (KB/s)")
        self.spin_global_kbps.set_subtitle("0 = Unlimited")
        self.spin_global_kbps.set_value(s.global_speed_limit_kbps)
        grp_lim.add(self.spin_global_kbps)
        page.add(grp_lim)

        self.add(page)

    def _build_save_to_page(self) -> None:
        s = self.manager.settings
        page = Adw.PreferencesPage(title="Save To", icon_name="folder-download-symbolic")

        grp_root = Adw.PreferencesGroup(
            title="Default Downloads Root",
            description="IDM-style automatic category subfolders live inside this directory.",
        )
        self.entry_root = Adw.EntryRow(title="Root Folder", text=s.downloads_root)
        grp_root.add(self.entry_root)
        page.add(grp_root)

        grp_cats = Adw.PreferencesGroup(title="Category Folders")
        for cat in DEFAULT_CATEGORIES:
            current = str(
                get_category_folder(
                    cat,
                    custom_folders=s.category_folders,
                    downloads_root=Path(s.downloads_root),
                )
            )
            row = Adw.EntryRow(title=cat, text=current)
            self._cat_entries[cat] = row
            grp_cats.add(row)
        page.add(grp_cats)

        self.add(page)

    def _build_proxy_page(self) -> None:
        s = self.manager.settings
        page = Adw.PreferencesPage(title="Proxy", icon_name="network-server-symbolic")

        grp = Adw.PreferencesGroup(
            title="Proxy Configuration",
            description="Supports http://, https://, and socks5:// URLs (e.g. socks5://127.0.0.1:1080).",
        )
        self.sw_proxy = Adw.SwitchRow(title="Use Proxy Server", active=s.proxy_enabled)
        grp.add(self.sw_proxy)

        self.entry_proxy = Adw.EntryRow(title="Proxy URL", text=s.proxy_url)
        grp.add(self.entry_proxy)
        page.add(grp)

        self.add(page)

    def _build_browser_page(self) -> None:
        s = self.manager.settings
        token = get_or_create_ipc_token()
        page = Adw.PreferencesPage(title="Browser", icon_name="web-browser-symbolic")

        grp_ipc = Adw.PreferencesGroup(
            title="Browser Extension Bridge (Chrome &amp; Firefox)",
            description=(
                "Native Messaging connects Chrome and Firefox directly to TurboDM "
                "and automatically launches TurboDM when closed. Auto-interception is OFF by default "
                "— use Right-Click 'Download with TurboDM' or the floating 'Download this video' button."
            ),
        )
        port_row = Adw.ActionRow(
            title="Local IPC Endpoint",
            subtitle=f"http://{IPC_HOST}:{s.ipc_port} (bound to localhost only)",
        )
        grp_ipc.add(port_row)

        token_row = Adw.ActionRow(
            title="IPC Auth Token",
            subtitle=f"{token[:10]}...{token[-6:]} (auto-read by Native Messaging host)",
        )
        copy_btn = Gtk.Button(label="Copy Token", valign=Gtk.Align.CENTER)
        copy_btn.connect("clicked", lambda *_: self._copy_text(token))
        token_row.add_suffix(copy_btn)
        grp_ipc.add(token_row)
        page.add(grp_ipc)

        grp_ext = Adw.PreferencesGroup(title="Loading Extensions")
        chrome_row = Adw.ActionRow(
            title="Google Chrome",
            subtitle="Open chrome://extensions → Enable Developer Mode → Load unpacked → select extensions/chrome",
        )
        grp_ext.add(chrome_row)
        ff_row = Adw.ActionRow(
            title="Mozilla Firefox",
            subtitle="Open about:debugging#/runtime/this-firefox → Load Temporary Add-on → select extensions/firefox/manifest.json",
        )
        grp_ext.add(ff_row)
        page.add(grp_ext)

        self.add(page)

    def _copy_text(self, text: str) -> None:
        display = Gdk.Display.get_default()
        if display:
            display.get_clipboard().set(text)

    def _on_closed(self, _dlg: Adw.PreferencesDialog) -> None:
        s = self.manager.settings
        s.show_progress_dialog = self.sw_progress.get_active()
        s.keep_running_in_background = self.sw_background.get_active()
        s.notify_on_complete = self.sw_notify.get_active()
        s.clipboard_monitor_enabled = self.sw_clipboard.get_active()
        s.autostart_enabled = self.sw_autostart.get_active()

        s.default_connections = int(self.spin_conns.get_value())
        s.max_retries = int(self.spin_retries.get_value())
        s.connect_timeout = float(self.spin_timeout.get_value())
        s.user_agent = self.entry_ua.get_text().strip() or s.user_agent
        s.verify_ssl = self.sw_ssl.get_active()

        s.global_speed_limit_enabled = self.sw_global_limit.get_active()
        s.global_speed_limit_kbps = int(self.spin_global_kbps.get_value())

        s.downloads_root = self.entry_root.get_text().strip() or s.downloads_root
        for cat, row in self._cat_entries.items():
            val = row.get_text().strip()
            if val:
                s.category_folders[cat] = val

        s.proxy_enabled = self.sw_proxy.get_active()
        s.proxy_url = self.entry_proxy.get_text().strip()

        self.manager.update_settings(s)
        sync_gnome_autostart(s.autostart_enabled)

        if self._on_saved:
            self._on_saved()
