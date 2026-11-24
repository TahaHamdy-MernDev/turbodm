"""Main GTK4 + Libadwaita ApplicationWindow styled 1:1 with the TurboDM Fedora Native design system."""

from __future__ import annotations

import os
import re
import subprocess
from pathlib import Path
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango  # noqa: E402

from turbodm.constants import APP_ID, APP_NAME, DEFAULT_CATEGORIES  # noqa: E402
from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.core.models import DownloadItem, DownloadStatus  # noqa: E402
from turbodm.ui.add_dialog import AddDownloadDialog  # noqa: E402
from turbodm.ui.batch_dialog import BatchDownloadDialog  # noqa: E402
from turbodm.ui.progress_dialog import DownloadProgressWindow  # noqa: E402
from turbodm.ui.properties_dialog import PropertiesDialog  # noqa: E402
from turbodm.ui.scheduler_dialog import SchedulerDialog  # noqa: E402
from turbodm.ui.settings_dialog import SettingsDialog  # noqa: E402
from turbodm.utils.formatting import format_bytes, format_eta, format_speed  # noqa: E402

CLIPBOARD_URL_RE = re.compile(
    r"^https?://[^\s]+\.(zip|rar|7z|tar|gz|xz|iso|rpm|deb|appimage|pdf|mp4|mkv|webm|mp3|flac|exe|msi|apk)(\?.*)?$",
    re.IGNORECASE,
)


def _create_brand_logo(pixel_size: int = 24) -> Gtk.Image:
    """Load the crisp TurboDM squircle SVG icon from project assets or installed theme."""
    asset_svg = Path(__file__).resolve().parents[3] / "assets" / f"{APP_ID}.svg"
    if asset_svg.exists():
        img = Gtk.Image.new_from_file(str(asset_svg))
    else:
        img = Gtk.Image.new_from_icon_name(APP_ID)
    img.set_pixel_size(pixel_size)
    return img


class DownloadRowWidget(Gtk.ListBoxRow):
    """Multi-column download row matching the TurboDM desktop mockup table."""

    def __init__(self, item: DownloadItem, window: MainWindow) -> None:
        super().__init__()
        self.download_id = item.id
        self._window = window
        self.add_css_class("download-row")

        root = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        self.set_child(root)

        # Column 1: Colored Category Icon + Filename + Slim Progress Bar
        file_col = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10, hexpand=True)
        cat_meta = DEFAULT_CATEGORIES.get(item.category, DEFAULT_CATEGORIES["General"])
        self.icon = Gtk.Image.new_from_icon_name(str(cat_meta["icon"]))
        self.icon.set_pixel_size(20)
        file_col.append(self.icon)

        name_stack = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=5, hexpand=True, valign=Gtk.Align.CENTER)
        self.name_label = Gtk.Label(
            label=item.filename,
            xalign=0,
            hexpand=True,
            ellipsize=Pango.EllipsizeMode.END,
        )
        self.name_label.add_css_class("download-filename")
        name_stack.append(self.name_label)

        self.progress_bar = Gtk.ProgressBar()
        self.progress_bar.set_size_request(180, -1)
        self.progress_bar.set_halign(Gtk.Align.START)
        self.progress_bar.add_css_class("row-progressbar")
        name_stack.append(self.progress_bar)
        file_col.append(name_stack)
        root.append(file_col)

        # Column 2: Size
        self.lbl_size = Gtk.Label(xalign=0, width_chars=17)
        self.lbl_size.add_css_class("mono-value")
        root.append(self.lbl_size)

        # Column 3: Status Pill
        status_wrap = Gtk.Box(width_request=125, halign=Gtk.Align.START, valign=Gtk.Align.CENTER)
        self.status_pill = Gtk.Label(label=item.status.value.capitalize())
        self.status_pill.add_css_class("status-pill")
        status_wrap.append(self.status_pill)
        root.append(status_wrap)

        # Column 4: Transfer Rate (Emerald when active)
        self.lbl_speed = Gtk.Label(xalign=0, width_chars=12)
        self.lbl_speed.add_css_class("mono-value")
        root.append(self.lbl_speed)

        # Column 5: ETA
        self.lbl_eta = Gtk.Label(xalign=0, width_chars=9)
        self.lbl_eta.add_css_class("mono-value")
        self.lbl_eta.add_css_class("text-muted")
        root.append(self.lbl_eta)

        # Column 6: Compact Quick Action Controls
        actions = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=2, valign=Gtk.Align.CENTER)
        self.btn_toggle = Gtk.Button(icon_name="media-playback-start-symbolic", tooltip_text="Resume / Pause")
        self.btn_toggle.add_css_class("flat")
        self.btn_toggle.connect("clicked", lambda *_: self._window.toggle_download(self.download_id))
        actions.append(self.btn_toggle)

        self.btn_details = Gtk.Button(
            icon_name="view-more-symbolic", tooltip_text="Open Download Progress Window"
        )
        self.btn_details.add_css_class("flat")
        self.btn_details.connect("clicked", lambda *_: self._window.open_progress_window(self.download_id))
        actions.append(self.btn_details)

        self.btn_folder = Gtk.Button(
            icon_name="folder-open-symbolic", tooltip_text="Open Containing Folder"
        )
        self.btn_folder.add_css_class("flat")
        self.btn_folder.connect("clicked", lambda *_: self._window.open_item_folder(self.download_id))
        actions.append(self.btn_folder)
        root.append(actions)

        # Right-click context menu
        right_click = Gtk.GestureClick(button=3)
        right_click.connect("pressed", self._on_right_click)
        self.add_controller(right_click)

        self.update_from_item(item)

    def update_from_item(self, item: DownloadItem) -> None:
        self.name_label.set_text(item.filename)
        cat_meta = DEFAULT_CATEGORIES.get(item.category, DEFAULT_CATEGORIES["General"])
        self.icon.set_from_icon_name(str(cat_meta["icon"]))

        for cls in ("icon-blue", "icon-emerald", "icon-amber", "icon-error", "icon-muted"):
            self.icon.remove_css_class(cls)
        for cls in ("status-downloading", "status-completed", "status-paused", "status-error", "status-queued"):
            self.status_pill.remove_css_class(cls)
        for cls in ("bar-complete", "bar-paused"):
            self.progress_bar.remove_css_class(cls)
        for cls in ("text-emerald", "text-muted", "text-error"):
            self.lbl_speed.remove_css_class(cls)

        st = item.status
        parts = max(1, len(item.segments) or item.max_connections)

        if st in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING):
            self.icon.add_css_class("icon-blue")
            self.status_pill.add_css_class("status-downloading")
            self.status_pill.set_text(f"{item.progress_percent:.1f}% • {parts}x")
            self.progress_bar.set_visible(True)
            self.progress_bar.set_fraction(item.progress_fraction)
            self.lbl_size.set_text(f"{format_bytes(item.downloaded_size)} / {format_bytes(item.total_size)}")
            self.lbl_speed.set_text(format_speed(item.speed) if item.speed > 0 else "--")
            self.lbl_speed.add_css_class("text-emerald")
            self.lbl_eta.set_text(format_eta(item.eta) if item.eta else "--")
            self.btn_toggle.set_icon_name("media-playback-pause-symbolic")
            self.btn_toggle.set_tooltip_text("Pause download")
            self.btn_toggle.set_visible(True)
        elif st == DownloadStatus.COMPLETED:
            self.icon.add_css_class("icon-emerald")
            self.status_pill.add_css_class("status-completed")
            self.status_pill.set_text("✓ Completed")
            self.progress_bar.add_css_class("bar-complete")
            self.progress_bar.set_visible(False)
            self.lbl_size.set_text(format_bytes(item.total_size))
            self.lbl_speed.set_text("—")
            self.lbl_speed.add_css_class("text-muted")
            self.lbl_eta.set_text("Done")
            self.btn_toggle.set_visible(False)
        elif st == DownloadStatus.PAUSED:
            self.icon.add_css_class("icon-amber")
            self.status_pill.add_css_class("status-paused")
            self.status_pill.set_text(f"Paused ({item.progress_percent:.0f}%)")
            self.progress_bar.add_css_class("bar-paused")
            self.progress_bar.set_visible(True)
            self.progress_bar.set_fraction(item.progress_fraction)
            self.lbl_size.set_text(f"{format_bytes(item.downloaded_size)} / {format_bytes(item.total_size)}")
            self.lbl_speed.set_text("0 KB/s")
            self.lbl_speed.add_css_class("text-muted")
            self.lbl_eta.set_text("—")
            self.btn_toggle.set_icon_name("media-playback-start-symbolic")
            self.btn_toggle.set_tooltip_text("Resume download")
            self.btn_toggle.set_visible(True)
        elif st == DownloadStatus.ERROR:
            self.icon.add_css_class("icon-error")
            self.status_pill.add_css_class("status-error")
            self.status_pill.set_text("Error")
            self.progress_bar.set_visible(False)
            self.lbl_size.set_text(f"{format_bytes(item.downloaded_size)} / {format_bytes(item.total_size)}")
            self.lbl_speed.set_text("Failed")
            self.lbl_speed.add_css_class("text-error")
            self.lbl_eta.set_text("—")
            self.btn_toggle.set_icon_name("view-refresh-symbolic")
            self.btn_toggle.set_tooltip_text("Retry download")
            self.btn_toggle.set_visible(True)
        else:
            self.icon.add_css_class("icon-muted")
            self.status_pill.add_css_class("status-queued")
            self.status_pill.set_text(st.value.capitalize())
            self.progress_bar.set_visible(False)
            self.lbl_size.set_text(format_bytes(item.total_size))
            self.lbl_speed.set_text("—")
            self.lbl_speed.add_css_class("text-muted")
            self.lbl_eta.set_text("Queued")
            self.btn_toggle.set_icon_name("media-playback-start-symbolic")
            self.btn_toggle.set_tooltip_text("Start download")
            self.btn_toggle.set_visible(True)

    def _on_right_click(self, _gesture: Gtk.GestureClick, _n_press: int, x: float, y: float) -> None:
        self._window.select_row_by_id(self.download_id)
        self._window.show_context_menu(self, self.download_id, x, y)


class MainWindow(Adw.ApplicationWindow):
    """Main IDM-style TurboDM window styled after the TurboDM Fedora Native website."""

    def __init__(self, app: Adw.Application, manager: DownloadManager) -> None:
        super().__init__(
            application=app,
            title=f"{APP_NAME} — Fedora Native Download Manager",
            default_width=1160,
            default_height=690,
        )
        self.manager = manager
        self._active_filter_type = "all"
        self._active_filter_value = ""
        self._search_text = ""
        self._sort_key = "created_at"
        self._rows: dict[str, DownloadRowWidget] = {}
        self._progress_windows: dict[str, DownloadProgressWindow] = {}
        self._last_clipboard_url = ""

        self._build_ui()
        self._wire_manager_events()
        self._rebuild_sidebar()
        self._rebuild_download_list()
        self._refresh_statusbar()

        GLib.timeout_add(1000, self._on_periodic_tick)
        self.connect("close-request", self._on_close_request)

    def _build_ui(self) -> None:
        self.toast_overlay = Adw.ToastOverlay()
        self.set_content(self.toast_overlay)

        outer = Adw.ToolbarView()
        self.toast_overlay.set_child(outer)

        # --- Top HeaderBar (Logo + Wordmark + Wayland/GTK Badge + Inline Filter + Controls) ---
        header = Adw.HeaderBar()
        header.set_show_title(False)

        brand_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10, valign=Gtk.Align.CENTER)
        brand_box.append(_create_brand_logo(24))
        brand_lbl = Gtk.Label(label=APP_NAME)
        brand_lbl.add_css_class("brand-title")
        brand_box.append(brand_lbl)

        session = os.environ.get("XDG_SESSION_TYPE", "wayland").capitalize()
        gtk_ver = f"GTK {Gtk.get_major_version()}.{Gtk.get_minor_version()}"
        env_badge = Gtk.Label(label=f"{session} • {gtk_ver}")
        env_badge.add_css_class("wayland-badge")
        brand_box.append(env_badge)
        header.pack_start(brand_box)

        # Right side of HeaderBar: Inline Filter Search + Speed Limiter + App Menu
        self.search_entry = Gtk.SearchEntry(placeholder_text="Filter downloads...")
        self.search_entry.set_size_request(210, -1)
        self.search_entry.connect("search-changed", self._on_search_changed)

        # Keep compatibility toggle attribute for Ctrl+F action
        self.search_toggle = Gtk.ToggleButton(visible=False)
        self.search_toggle.connect("toggled", lambda *_: self.search_entry.grab_focus())

        self.speed_limit_btn = Gtk.ToggleButton(
            icon_name="speedometer-symbolic",
            tooltip_text="Toggle Global Speed Limiter",
            active=self.manager.settings.global_speed_limit_enabled,
        )
        self.speed_limit_btn.connect("toggled", self._on_speed_limit_toggled)

        menu = Gio.Menu()
        menu.append("Batch Download...", "app.batch")
        menu.append("Scheduler & Queues...", "app.scheduler")
        menu.append("Options...", "app.settings")
        menu.append("About TurboDM", "app.about")
        menu.append("Quit TurboDM", "app.quit")
        menu_btn = Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu)

        header.pack_end(menu_btn)
        header.pack_end(self.speed_limit_btn)
        header.pack_end(self.search_entry)
        outer.add_top_bar(header)

        # --- Horizontal IDM Action Toolbar matching Website Mockup ---
        toolbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        toolbar.add_css_class("idm-toolbar")

        def _tbtn(
            icon: str,
            label: str,
            tooltip: str,
            cb: Any,
            style: str = "ghost",
            icon_cls: str = "",
        ) -> Gtk.Button:
            btn = Gtk.Button(tooltip_text=tooltip)
            btn.add_css_class(f"idm-btn-{style}")
            inner = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6, valign=Gtk.Align.CENTER)
            img = Gtk.Image.new_from_icon_name(icon)
            img.set_pixel_size(15)
            if icon_cls:
                img.add_css_class(icon_cls)
            inner.append(img)
            inner.append(Gtk.Label(label=label))
            btn.set_child(inner)
            btn.connect("clicked", cb)
            toolbar.append(btn)
            return btn

        _tbtn("list-add-symbolic", "Add URL", "Add a new download URL (Ctrl+N)", lambda *_: self.open_add_dialog(), style="primary")
        _tbtn("media-playback-start-symbolic", "Resume", "Resume selected download", lambda *_: self._resume_selected(), style="chip", icon_cls="icon-emerald")
        _tbtn("media-playback-pause-symbolic", "Stop", "Pause selected download", lambda *_: self._pause_selected(), style="chip", icon_cls="icon-amber")
        _tbtn("media-playback-stop-symbolic", "Stop All", "Pause all active downloads", lambda *_: self.manager.pause_all(), style="chip")

        sep1 = Gtk.Separator(orientation=Gtk.Orientation.VERTICAL)
        sep1.set_margin_start(4)
        sep1.set_margin_end(4)
        toolbar.append(sep1)

        _tbtn("view-list-symbolic", "Batch", "Batch download wildcards or URL list", lambda *_: self.open_batch_dialog())
        _tbtn("alarm-symbolic", "Scheduler", "Configure queues & schedules", lambda *_: self.open_scheduler_dialog())
        _tbtn("media-playlist-consecutive-symbolic", "Start Queue", "Start active queue", lambda *_: self._start_active_queue())
        _tbtn("edit-delete-symbolic", "Delete", "Remove selected download", lambda *_: self._delete_selected(False))
        _tbtn("edit-clear-all-symbolic", "Clear Done", "Clear completed entries", lambda *_: self._clear_completed())
        _tbtn("emblem-system-symbolic", "Options", "TurboDM Options & Browser Setup", lambda *_: self.open_settings_dialog())

        toolbar.append(Gtk.Box(hexpand=True))

        self.toolbar_speed_lbl = Gtk.Label(label="Total: 0 B/s")
        self.toolbar_speed_lbl.add_css_class("toolbar-speed-total")
        toolbar.append(self.toolbar_speed_lbl)

        outer.add_top_bar(toolbar)

        # --- Center Split: Recessed #090C10 Sidebar + Main Table ---
        paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL, position=235)
        outer.set_content(paned)

        sb_scroll = Gtk.ScrolledWindow(vexpand=True)
        sb_scroll.add_css_class("idm-sidebar")
        self.sidebar_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.sidebar_list.connect("row-selected", self._on_sidebar_selected)
        sb_scroll.set_child(self.sidebar_list)
        paned.set_start_child(sb_scroll)

        # Right Pane: Filter Bar + Table Column Headers + Download Rows
        right_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0, hexpand=True, vexpand=True)
        paned.set_end_child(right_box)

        # Table Column Header Strip matching Website
        tbl_hdr = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        tbl_hdr.add_css_class("table-header-bar")

        self.filter_title_label = Gtk.Label(label="File Name — All Downloads", xalign=0, hexpand=True)
        self.filter_title_label.add_css_class("table-col-hdr")
        tbl_hdr.append(self.filter_title_label)

        for title, chars in (("Size", 17), ("Status", 16), ("Transfer Rate", 12), ("ETA", 9)):
            lbl = Gtk.Label(label=title, xalign=0, width_chars=chars)
            lbl.add_css_class("table-col-hdr")
            tbl_hdr.append(lbl)

        # Compact sort dropdown aligned right over the action buttons column
        self._sort_keys = ["created_at", "filename", "total_size", "status", "category"]
        self.sort_dropdown = Gtk.DropDown.new_from_strings(["Recent", "Name", "Size", "Status", "Category"])
        self.sort_dropdown.set_tooltip_text("Sort downloads")
        self.sort_dropdown.connect("notify::selected", self._on_sort_changed)
        tbl_hdr.append(self.sort_dropdown)

        right_box.append(tbl_hdr)

        # Download List + Empty State Stack
        self.content_stack = Gtk.Stack(vexpand=True, hexpand=True)
        right_box.append(self.content_stack)

        dl_scroll = Gtk.ScrolledWindow(vexpand=True, hexpand=True)
        self.download_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.download_list.add_css_class("download-listbox")
        self.download_list.connect("row-activated", self._on_download_row_activated)
        dl_scroll.set_child(self.download_list)
        self.content_stack.add_named(dl_scroll, "list")

        empty_status = Adw.StatusPage(
            icon_name="folder-download-symbolic",
            title="No Downloads Yet",
            description="Click 'Add URL' to accelerate a file up to 32 parallel segments, or right-click any link in Chrome / Firefox.",
        )
        self.content_stack.add_named(empty_status, "empty")

        # --- Recessed Bottom Status Bar ---
        statusbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=16)
        statusbar.add_css_class("idm-statusbar")
        self.sb_count = Gtk.Label(label="0 downloads", xalign=0)
        self.sb_count.add_css_class("mono-value")
        self.sb_speed = Gtk.Label(label="Speed: 0 B/s", xalign=0)
        self.sb_speed.add_css_class("mono-value")
        self.sb_speed.add_css_class("text-emerald")
        self.sb_limiter = Gtk.Label(label="Limiter: Off", xalign=0)
        self.sb_limiter.add_css_class("mono-value")
        self.sb_ipc = Gtk.Label(
            label=f"● Browser Bridge: 127.0.0.1:{self.manager.settings.ipc_port}",
            xalign=1,
            hexpand=True,
        )
        self.sb_ipc.add_css_class("mono-value")
        self.sb_ipc.add_css_class("text-muted")

        statusbar.append(self.sb_count)
        statusbar.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL))
        statusbar.append(self.sb_speed)
        statusbar.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL))
        statusbar.append(self.sb_limiter)
        statusbar.append(self.sb_ipc)
        outer.add_bottom_bar(statusbar)

    # ---------- Manager Event Wiring ----------

    def _wire_manager_events(self) -> None:
        self.manager.on_item_added.append(lambda item: GLib.idle_add(self._on_item_added_ui, item))
        self.manager.on_item_updated.append(lambda item: GLib.idle_add(self._on_item_updated_ui, item))
        self.manager.on_item_removed.append(lambda dl_id: GLib.idle_add(self._on_item_removed_ui, dl_id))
        self.manager.on_queue_changed.append(lambda _q: GLib.idle_add(self._rebuild_sidebar))

    def _on_item_added_ui(self, item: DownloadItem) -> bool:
        self._rebuild_download_list()
        self._rebuild_sidebar()
        self._refresh_statusbar()
        return False

    def _on_item_updated_ui(self, item: DownloadItem) -> bool:
        row = self._rows.get(item.id)
        if row:
            row.update_from_item(item)
        elif self._matches_filter(item):
            self._rebuild_download_list()

        pw = self._progress_windows.get(item.id)
        if pw and pw.get_visible():
            pw.refresh(item)
        return False

    def _on_item_removed_ui(self, download_id: str) -> bool:
        pw = self._progress_windows.pop(download_id, None)
        if pw:
            pw.close()
        self._rebuild_download_list()
        self._rebuild_sidebar()
        self._refresh_statusbar()
        return False

    # ---------- Sidebar ----------

    def _rebuild_sidebar(self) -> bool:
        while (child := self.sidebar_list.get_first_child()) is not None:
            self.sidebar_list.remove(child)

        items = self.manager.list_downloads()

        def _add_header(text: str) -> None:
            row = Gtk.ListBoxRow(selectable=False, activatable=False)
            lbl = Gtk.Label(label=text, xalign=0)
            lbl.add_css_class("idm-sidebar-header")
            row.set_child(lbl)
            self.sidebar_list.append(row)

        def _add_nav(
            ftype: str,
            fval: str,
            title: str,
            icon: str | None,
            count: int,
            dot_cls: str = "",
        ) -> Gtk.ListBoxRow:
            row = Gtk.ListBoxRow()
            row.filter_type = ftype  # type: ignore[attr-defined]
            row.filter_value = fval  # type: ignore[attr-defined]
            row.filter_title = title  # type: ignore[attr-defined]
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=9)
            box.set_margin_top(6)
            box.set_margin_bottom(6)
            box.set_margin_start(8)
            box.set_margin_end(8)
            if dot_cls:
                dot = Gtk.Box(valign=Gtk.Align.CENTER)
                dot.add_css_class(dot_cls)
                box.append(dot)
            elif icon:
                img = Gtk.Image.new_from_icon_name(icon)
                img.set_pixel_size(15)
                box.append(img)
            box.append(Gtk.Label(label=title, xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END))
            cnt = Gtk.Label(label=str(count))
            cnt.add_css_class("mono-value")
            cnt.add_css_class("text-muted")
            box.append(cnt)
            row.set_child(box)
            self.sidebar_list.append(row)
            return row

        _add_header("CATEGORIES")
        all_row = _add_nav("all", "", "All Downloads", "folder-download-symbolic", len(items))
        selected_row = all_row if self._active_filter_type == "all" else None

        for cat, meta in DEFAULT_CATEGORIES.items():
            cnt = sum(1 for i in items if i.category == cat)
            r = _add_nav("category", cat, cat, str(meta["icon"]), cnt)
            if self._active_filter_type == "category" and self._active_filter_value == cat:
                selected_row = r

        _add_header("STATUS")
        unfin_cnt = sum(1 for i in items if i.status != DownloadStatus.COMPLETED)
        fin_cnt = sum(1 for i in items if i.status == DownloadStatus.COMPLETED)
        r_unfin = _add_nav("status", "unfinished", "Unfinished", "content-loading-symbolic", unfin_cnt)
        r_fin = _add_nav("status", "finished", "Finished", "emblem-ok-symbolic", fin_cnt)
        if self._active_filter_type == "status" and self._active_filter_value == "unfinished":
            selected_row = r_unfin
        elif self._active_filter_type == "status" and self._active_filter_value == "finished":
            selected_row = r_fin

        _add_header("QUEUES")
        for q in self.manager.queues.values():
            q_cnt = sum(1 for i in items if i.queue_id == q.id)
            dot = "dot-emerald" if q.is_running else ("dot-amber" if q.schedule_enabled else "dot-muted")
            r_q = _add_nav("queue", q.id, q.name, None, q_cnt, dot_cls=dot)
            if self._active_filter_type == "queue" and self._active_filter_value == q.id:
                selected_row = r_q

        if selected_row:
            self.sidebar_list.select_row(selected_row)
        return False

    def _on_sidebar_selected(self, _lb: Gtk.ListBox, row: Gtk.ListBoxRow | None) -> None:
        if row is None or not hasattr(row, "filter_type"):
            return
        self._active_filter_type = row.filter_type  # type: ignore[attr-defined]
        self._active_filter_value = row.filter_value  # type: ignore[attr-defined]
        self.filter_title_label.set_text(f"File Name — {row.filter_title}")  # type: ignore[attr-defined]
        self._rebuild_download_list()

    # ---------- Download List ----------

    def _matches_filter(self, item: DownloadItem) -> bool:
        if self._active_filter_type == "category" and item.category != self._active_filter_value:
            return False
        if self._active_filter_type == "status":
            if self._active_filter_value == "finished" and item.status != DownloadStatus.COMPLETED:
                return False
            if self._active_filter_value == "unfinished" and item.status == DownloadStatus.COMPLETED:
                return False
        if self._active_filter_type == "queue" and item.queue_id != self._active_filter_value:
            return False
        if self._search_text:
            q = self._search_text.lower()
            if q not in item.filename.lower() and q not in item.url.lower() and q not in item.category.lower():
                return False
        return True

    def _rebuild_download_list(self) -> None:
        selected_id = self._get_selected_download_id()
        while (child := self.download_list.get_first_child()) is not None:
            self.download_list.remove(child)
        self._rows.clear()

        items = [i for i in self.manager.list_downloads() if self._matches_filter(i)]

        if self._sort_key == "filename":
            items.sort(key=lambda x: x.filename.lower())
        elif self._sort_key == "total_size":
            items.sort(key=lambda x: x.total_size or 0, reverse=True)
        elif self._sort_key == "status":
            items.sort(key=lambda x: x.status.value)
        elif self._sort_key == "category":
            items.sort(key=lambda x: x.category)
        else:
            items.sort(key=lambda x: x.created_at, reverse=True)

        if not items:
            self.content_stack.set_visible_child_name("empty")
            return

        self.content_stack.set_visible_child_name("list")
        for item in items:
            row = DownloadRowWidget(item, self)
            self._rows[item.id] = row
            self.download_list.append(row)
            if item.id == selected_id:
                self.download_list.select_row(row)

    def _get_selected_download_id(self) -> str | None:
        row = self.download_list.get_selected_row()
        if isinstance(row, DownloadRowWidget):
            return row.download_id
        return None

    def select_row_by_id(self, download_id: str) -> None:
        row = self._rows.get(download_id)
        if row:
            self.download_list.select_row(row)

    # ---------- Context Menu ----------

    def show_context_menu(self, widget: Gtk.Widget, download_id: str, x: float, y: float) -> None:
        item = self.manager.get_download(download_id)
        if not item:
            return

        popover = Gtk.Popover()
        popover.set_parent(widget)
        rect = Gdk.Rectangle()
        rect.x, rect.y, rect.width, rect.height = int(x), int(y), 1, 1
        popover.set_pointing_to(rect)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.set_margin_top(4)
        box.set_margin_bottom(4)
        box.set_margin_start(4)
        box.set_margin_end(4)
        popover.set_child(box)

        def _add_item(label: str, cb: Any) -> None:
            btn = Gtk.Button(label=label)
            btn.add_css_class("flat")
            btn.set_halign(Gtk.Align.FILL)
            lbl = btn.get_child()
            if isinstance(lbl, Gtk.Label):
                lbl.set_xalign(0)
            btn.connect("clicked", lambda *_: (popover.popdown(), cb()))
            box.append(btn)

        if item.status in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING):
            _add_item("Pause Download", lambda: self.manager.pause_download(download_id))
        elif item.status != DownloadStatus.COMPLETED:
            _add_item("Resume Download", lambda: self._start_and_show_progress(download_id))

        _add_item("Show Progress Window", lambda: self.open_progress_window(download_id))
        if item.status == DownloadStatus.COMPLETED and item.filepath.exists():
            _add_item("Open File", lambda: self.open_item_file(download_id))
        _add_item("Open Containing Folder", lambda: self.open_item_folder(download_id))
        box.append(Gtk.Separator())
        _add_item("Copy Download URL", lambda: self._copy_url(item.url))
        _add_item("Redownload from Start", lambda: self._redownload(download_id))
        _add_item("Properties & Checksum...", lambda: self.open_properties_dialog(download_id))
        box.append(Gtk.Separator())
        _add_item("Remove from List", lambda: self.manager.delete_download(download_id, delete_file=False))
        _add_item("Delete File from Disk", lambda: self.manager.delete_download(download_id, delete_file=True))

        popover.popup()

    # ---------- Actions & Dialog Openers ----------

    def open_add_dialog(
        self,
        initial_url: str = "",
        initial_filename: str = "",
        referer: str = "",
        cookies: str = "",
        user_agent: str = "",
        headers: dict[str, str] | None = None,
        is_media_hint: bool | None = None,
    ) -> None:
        def _on_created(item: DownloadItem, started_now: bool) -> None:
            if started_now and self.manager.settings.show_progress_dialog:
                self.open_progress_window(item.id)
            self.show_toast(f"Added: {item.filename}")

        dlg = AddDownloadDialog(
            parent=self,
            manager=self.manager,
            initial_url=initial_url,
            initial_filename=initial_filename,
            referer=referer,
            cookies=cookies,
            user_agent=user_agent,
            headers=headers,
            is_media_hint=is_media_hint,
            on_created=_on_created,
        )
        dlg.present()

    def open_progress_window(self, download_id: str) -> None:
        item = self.manager.get_download(download_id)
        if not item:
            return
        existing = self._progress_windows.get(download_id)
        if existing:
            existing.refresh(item)
            existing.present()
            return
        pw = DownloadProgressWindow(parent=None, manager=self.manager, item=item)
        pw.connect("close-request", lambda _w: self._progress_windows.pop(download_id, None) and False)
        self._progress_windows[download_id] = pw
        pw.present()

    def open_batch_dialog(
        self,
        initial_urls: list[str] | None = None,
        referer: str = "",
        cookies: str = "",
        user_agent: str = "",
    ) -> None:
        dlg = BatchDownloadDialog(
            parent=self,
            manager=self.manager,
            initial_urls=initial_urls,
            referer=referer,
            cookies=cookies,
            user_agent=user_agent,
        )
        dlg.present()

    def open_scheduler_dialog(self) -> None:
        dlg = SchedulerDialog(parent=self, manager=self.manager)
        dlg.present()

    def open_properties_dialog(self, download_id: str) -> None:
        item = self.manager.get_download(download_id)
        if not item:
            return
        dlg = PropertiesDialog(parent=self, manager=self.manager, item=item)
        dlg.present()

    def open_settings_dialog(self) -> None:
        dlg = SettingsDialog(manager=self.manager, on_saved=self._refresh_statusbar)
        dlg.present(self)

    def toggle_download(self, download_id: str) -> None:
        item = self.manager.get_download(download_id)
        if not item:
            return
        if item.status in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING):
            self.manager.pause_download(download_id)
        else:
            self._start_and_show_progress(download_id)

    def _start_and_show_progress(self, download_id: str) -> None:
        if self.manager.start_download(download_id) and self.manager.settings.show_progress_dialog:
            self.open_progress_window(download_id)

    def _resume_selected(self) -> None:
        dl_id = self._get_selected_download_id()
        if dl_id:
            self._start_and_show_progress(dl_id)

    def _pause_selected(self) -> None:
        dl_id = self._get_selected_download_id()
        if dl_id:
            self.manager.pause_download(dl_id)

    def _delete_selected(self, delete_file: bool) -> None:
        dl_id = self._get_selected_download_id()
        if dl_id:
            self.manager.delete_download(dl_id, delete_file=delete_file)

    def _clear_completed(self) -> None:
        count = self.manager.delete_completed()
        self.show_toast(f"Cleared {count} completed download(s)")

    def _redownload(self, download_id: str) -> None:
        self.manager.redownload(download_id)
        if self.manager.settings.show_progress_dialog:
            self.open_progress_window(download_id)

    def _start_active_queue(self) -> None:
        q_id = self._active_filter_value if self._active_filter_type == "queue" else "main"
        self.manager.start_queue(q_id)
        self.show_toast(f"Started queue: {self.manager.queues[q_id].name}")

    def _stop_active_queue(self) -> None:
        q_id = self._active_filter_value if self._active_filter_type == "queue" else "main"
        self.manager.stop_queue(q_id)
        self.show_toast(f"Stopped queue: {self.manager.queues[q_id].name}")

    def open_item_file(self, download_id: str) -> None:
        item = self.manager.get_download(download_id)
        if item and item.filepath.exists():
            subprocess.Popen(["xdg-open", str(item.filepath)])

    def open_item_folder(self, download_id: str) -> None:
        item = self.manager.get_download(download_id)
        if item and Path(item.save_dir).exists():
            subprocess.Popen(["xdg-open", str(item.save_dir)])

    def _copy_url(self, url: str) -> None:
        display = Gdk.Display.get_default()
        if display:
            display.get_clipboard().set(url)
            self.show_toast("Download URL copied to clipboard")

    def _on_download_row_activated(self, _lb: Gtk.ListBox, row: Gtk.ListBoxRow) -> None:
        if not isinstance(row, DownloadRowWidget):
            return
        item = self.manager.get_download(row.download_id)
        if not item:
            return
        if item.status == DownloadStatus.COMPLETED and item.filepath.exists():
            self.open_item_file(item.id)
        else:
            self.open_progress_window(item.id)

    def _on_search_changed(self, entry: Gtk.SearchEntry) -> None:
        self._search_text = entry.get_text().strip()
        self._rebuild_download_list()

    def _on_sort_changed(self, dropdown: Gtk.DropDown, _pspec: Any) -> None:
        idx = dropdown.get_selected()
        if 0 <= idx < len(self._sort_keys):
            self._sort_key = self._sort_keys[idx]
            self._rebuild_download_list()

    def _on_speed_limit_toggled(self, btn: Gtk.ToggleButton) -> None:
        enabled = btn.get_active()
        kbps = self.manager.settings.global_speed_limit_kbps
        if enabled and kbps <= 0:
            kbps = 1024
        self.manager.set_global_speed_limit(enabled, kbps)
        self._refresh_statusbar()

    def _refresh_statusbar(self) -> None:
        total_items = len(self.manager.list_downloads())
        active = self.manager.get_active_count()
        speed = self.manager.get_total_speed()
        speed_str = format_speed(speed)
        self.sb_count.set_text(f"{total_items} downloads ({active} active)")
        self.sb_speed.set_text(f"Speed: {speed_str}")
        self.toolbar_speed_lbl.set_text(f"Total: {speed_str}")

        s = self.manager.settings
        if s.global_speed_limit_enabled and s.global_speed_limit_kbps > 0:
            self.sb_limiter.set_text(f"Limiter: {s.global_speed_limit_kbps} KB/s")
            self.speed_limit_btn.add_css_class("speed-limiter-active")
        else:
            self.sb_limiter.set_text("Limiter: Off")
            self.speed_limit_btn.remove_css_class("speed-limiter-active")

    def _on_periodic_tick(self) -> bool:
        self._refresh_statusbar()
        if self.manager.settings.clipboard_monitor_enabled:
            self._poll_clipboard()
        return True

    def _poll_clipboard(self) -> None:
        display = Gdk.Display.get_default()
        if not display:
            return
        cb = display.get_clipboard()

        def _on_text(clipboard: Gdk.Clipboard, res: Gio.AsyncResult) -> None:
            try:
                text = clipboard.read_text_finish(res)
            except GLib.Error:
                return
            if not text:
                return
            url = text.strip()
            if url != self._last_clipboard_url and CLIPBOARD_URL_RE.match(url):
                self._last_clipboard_url = url
                self.present()
                self.open_add_dialog(initial_url=url)

        cb.read_text_async(None, _on_text)

    def show_toast(self, message: str) -> None:
        self.toast_overlay.add_toast(Adw.Toast(title=message, timeout=3))

    def _on_close_request(self, _win: Gtk.Window) -> bool:
        if self.manager.settings.keep_running_in_background:
            self.set_visible(False)
            return True
        return False
