"""IDM-style separate 'Download Progress' window with live multi-segment bar & connection details."""

from __future__ import annotations

import subprocess
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk, Pango  # noqa: E402

from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.core.models import DownloadItem, DownloadStatus, SegmentStatus  # noqa: E402
from turbodm.ui.segment_bar import SegmentProgressBar  # noqa: E402
from turbodm.utils.formatting import format_bytes, format_eta, format_speed  # noqa: E402


class DownloadProgressWindow(Adw.Window):
    """Per-download floating progress dialog inspired by IDM's download status window."""

    def __init__(
        self,
        parent: Gtk.Window | None,
        manager: DownloadManager,
        item: DownloadItem,
    ) -> None:
        super().__init__(
            title=f"{item.filename} — TurboDM",
            transient_for=parent,
            modal=False,
            default_width=560,
            default_height=410,
        )
        self.manager = manager
        self.download_id = item.id
        self._item = item
        self._auto_close_on_complete = False
        self._details_visible = True
        self._seg_rows: dict[int, tuple[Gtk.Label, Gtk.Label, Gtk.Label]] = {}

        self._build_ui()
        self.refresh(item)

    def _build_ui(self) -> None:
        toolbar_view = Adw.ToolbarView()
        self.set_content(toolbar_view)

        header = Adw.HeaderBar()
        self._title_widget = Adw.WindowTitle(title=self._item.filename, subtitle="Downloading")
        header.set_title_widget(self._title_widget)
        toolbar_view.add_top_bar(header)

        main_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        main_box.set_margin_top(10)
        main_box.set_margin_bottom(10)
        main_box.set_margin_start(14)
        main_box.set_margin_end(14)
        toolbar_view.set_content(main_box)

        # Notebook tabs (just like IDM: Download status | Speed limiter | Options on completion)
        notebook = Gtk.Notebook()
        notebook.set_vexpand(True)
        main_box.append(notebook)

        # --- Tab 1: Download Status ---
        status_tab = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        status_tab.set_margin_top(10)
        status_tab.set_margin_bottom(10)
        status_tab.set_margin_start(10)
        status_tab.set_margin_end(10)

        # URL line
        self.url_label = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.MIDDLE, selectable=True)
        self.url_label.add_css_class("download-meta")
        status_tab.append(self.url_label)

        # Key-value telemetry grid
        grid = Gtk.Grid(column_spacing=16, row_spacing=5)
        status_tab.append(grid)

        def _row(r: int, key: str) -> Gtk.Label:
            k_lbl = Gtk.Label(label=key, xalign=0)
            k_lbl.add_css_class("dim-label")
            v_lbl = Gtk.Label(label="--", xalign=0, hexpand=True)
            v_lbl.add_css_class("mono-value")
            grid.attach(k_lbl, 0, r, 1, 1)
            grid.attach(v_lbl, 1, r, 1, 1)
            return v_lbl

        self.lbl_status = _row(0, "Status:")
        self.lbl_size = _row(1, "File size:")
        self.lbl_downloaded = _row(2, "Downloaded:")
        self.lbl_speed = _row(3, "Transfer rate:")
        self.lbl_eta = _row(4, "Time left:")
        self.lbl_resume = _row(5, "Resume capability:")

        # Signature IDM multi-segment visual progress bar
        self.segment_bar = SegmentProgressBar(height=28)
        status_tab.append(self.segment_bar)

        # Collapsible Per-Connection Details Table
        self.details_frame = Gtk.Frame(label="Start positions and download progress by connections")
        self.details_frame.set_vexpand(True)
        seg_scroll = Gtk.ScrolledWindow(min_content_height=110, vexpand=True)
        self.seg_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.seg_list.add_css_class("boxed-list")
        seg_scroll.set_child(self.seg_list)
        self.details_frame.set_child(seg_scroll)
        status_tab.append(self.details_frame)

        notebook.append_page(status_tab, Gtk.Label(label="Download status"))

        # --- Tab 2: Speed Limiter ---
        limiter_tab = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        limiter_tab.set_margin_top(16)
        limiter_tab.set_margin_start(16)
        limiter_tab.set_margin_end(16)

        self.limit_check = Gtk.CheckButton(label="Use speed limiter for this download")
        self.limit_check.set_active(self._item.speed_limit_kbps > 0)
        self.limit_check.connect("toggled", self._on_speed_limit_changed)
        limiter_tab.append(self.limit_check)

        limit_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        limit_row.append(Gtk.Label(label="Maximum download speed (KB/s):"))
        adj = Gtk.Adjustment(
            value=self._item.speed_limit_kbps or 512,
            lower=10,
            upper=500000,
            step_increment=64,
            page_increment=512,
        )
        self.limit_spin = Gtk.SpinButton(adjustment=adj, numeric=True)
        self.limit_spin.set_sensitive(self._item.speed_limit_kbps > 0)
        self.limit_spin.connect("value-changed", self._on_speed_limit_changed)
        limit_row.append(self.limit_spin)
        limiter_tab.append(limit_row)

        hint = Gtk.Label(
            label="Tip: Speed changes apply immediately without restarting the download.",
            xalign=0,
            wrap=True,
        )
        hint.add_css_class("dim-label")
        limiter_tab.append(hint)
        notebook.append_page(limiter_tab, Gtk.Label(label="Speed limiter"))

        # --- Tab 3: Options on Completion ---
        comp_tab = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        comp_tab.set_margin_top(16)
        comp_tab.set_margin_start(16)
        comp_tab.set_margin_end(16)

        self.auto_close_check = Gtk.CheckButton(label="Close this dialog when download completes")
        self.auto_close_check.connect(
            "toggled", lambda btn: setattr(self, "_auto_close_on_complete", btn.get_active())
        )
        comp_tab.append(self.auto_close_check)

        action_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        action_row.append(Gtk.Label(label="When complete:"))
        self._actions = ["notify", "open_file", "open_folder", "command"]
        self.action_dropdown = Gtk.DropDown.new_from_strings(
            ["Show notification only", "Open downloaded file", "Open containing folder", "Run custom command"]
        )
        if self._item.on_complete_action in self._actions:
            self.action_dropdown.set_selected(self._actions.index(self._item.on_complete_action))
        self.action_dropdown.connect("notify::selected", self._on_complete_action_changed)
        action_row.append(self.action_dropdown)
        comp_tab.append(action_row)

        self.cmd_entry = Gtk.Entry(
            placeholder_text="Command (placeholders: {file}, {dir})",
            text=self._item.on_complete_command,
        )
        self.cmd_entry.set_sensitive(self._item.on_complete_action == "command")
        self.cmd_entry.connect("changed", self._on_complete_cmd_changed)
        comp_tab.append(self.cmd_entry)

        notebook.append_page(comp_tab, Gtk.Label(label="Options on completion"))

        # --- Footer Controls ---
        footer = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        footer.set_margin_top(6)
        footer.set_margin_bottom(10)
        footer.set_margin_start(14)
        footer.set_margin_end(14)

        self.details_btn = Gtk.Button(label="<< Hide details")
        self.details_btn.connect("clicked", self._toggle_details)
        footer.append(self.details_btn)

        footer.append(Gtk.Box(hexpand=True))

        self.open_folder_btn = Gtk.Button(label="Open Folder")
        self.open_folder_btn.set_visible(False)
        self.open_folder_btn.connect("clicked", self._on_open_folder)
        footer.append(self.open_folder_btn)

        self.open_file_btn = Gtk.Button(label="Open")
        self.open_file_btn.add_css_class("suggested-action")
        self.open_file_btn.set_visible(False)
        self.open_file_btn.connect("clicked", self._on_open_file)
        footer.append(self.open_file_btn)

        self.pause_btn = Gtk.Button(label="Pause")
        self.pause_btn.connect("clicked", self._on_pause_resume)
        footer.append(self.pause_btn)

        self.cancel_btn = Gtk.Button(label="Cancel")
        self.cancel_btn.connect("clicked", self._on_cancel_or_close)
        footer.append(self.cancel_btn)

        toolbar_view.add_bottom_bar(footer)

    def refresh(self, item: DownloadItem) -> None:
        self._item = item
        pct = item.progress_percent
        self.set_title(f"{pct:.1f}% — {item.filename} — TurboDM")
        self._title_widget.set_title(item.filename)
        self._title_widget.set_subtitle(f"{item.status.value.capitalize()} • {pct:.1f}%")

        self.url_label.set_text(item.url)
        status_text = item.status.value.capitalize()
        if item.status == DownloadStatus.DOWNLOADING:
            active_conns = sum(
                1 for s in item.segments if s.status in (SegmentStatus.RECEIVING, SegmentStatus.CONNECTING)
            )
            status_text = f"Receiving data ({max(1, active_conns)} connections)..."
        elif item.status == DownloadStatus.ERROR and item.error_message:
            status_text = f"Error: {item.error_message}"
        self.lbl_status.set_text(status_text)

        self.lbl_size.set_text(format_bytes(item.total_size))
        self.lbl_downloaded.set_text(f"{format_bytes(item.downloaded_size)} ({pct:.2f} %)")
        self.lbl_speed.set_text(format_speed(item.speed))
        self.lbl_eta.set_text(format_eta(item.eta))
        self.lbl_resume.set_text("Yes" if item.resumable else "No")

        self.segment_bar.update_item(item)
        self._refresh_segment_table(item)

        is_done = item.status == DownloadStatus.COMPLETED
        is_active = item.status in (
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PROBING,
            DownloadStatus.MERGING,
        )
        self.open_file_btn.set_visible(is_done)
        self.open_folder_btn.set_visible(is_done)
        self.pause_btn.set_visible(not is_done)
        self.pause_btn.set_label("Pause" if is_active else "Resume")
        self.cancel_btn.set_label("Close" if is_done else "Cancel")

        if is_done and self._auto_close_on_complete:
            self.close()

    def _refresh_segment_table(self, item: DownloadItem) -> None:
        segments = list(item.segments)
        for seg in segments:
            if seg.index not in self._seg_rows:
                row_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
                row_box.set_margin_top(4)
                row_box.set_margin_bottom(4)
                row_box.set_margin_start(10)
                row_box.set_margin_end(10)

                lbl_n = Gtk.Label(label=f"#{seg.index + 1}", width_chars=4, xalign=0)
                lbl_n.add_css_class("mono-value")
                lbl_dl = Gtk.Label(width_chars=18, xalign=0)
                lbl_dl.add_css_class("mono-value")
                lbl_info = Gtk.Label(xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END)
                lbl_info.add_css_class("download-meta")

                row_box.append(lbl_n)
                row_box.append(lbl_dl)
                row_box.append(lbl_info)
                self.seg_list.append(row_box)
                self._seg_rows[seg.index] = (lbl_n, lbl_dl, lbl_info)

            _, lbl_dl, lbl_info = self._seg_rows[seg.index]
            if seg.total_bytes > 0:
                lbl_dl.set_text(f"{format_bytes(seg.downloaded)} / {format_bytes(seg.total_bytes)}")
            else:
                lbl_dl.set_text(format_bytes(seg.downloaded))

            if seg.status == SegmentStatus.RECEIVING:
                lbl_info.set_text(f"Receiving data... ({format_speed(seg.speed)})")
            elif seg.status == SegmentStatus.CONNECTING:
                lbl_info.set_text("Connecting...")
            elif seg.status == SegmentStatus.COMPLETED:
                lbl_info.set_text("Completed")
            elif seg.status == SegmentStatus.ERROR:
                lbl_info.set_text(f"Error: {seg.error}")
            else:
                lbl_info.set_text(seg.status.value.capitalize())

    def _toggle_details(self, _btn: Gtk.Button) -> None:
        self._details_visible = not self._details_visible
        self.details_frame.set_visible(self._details_visible)
        self.details_btn.set_label("<< Hide details" if self._details_visible else "Show details >>")

    def _on_speed_limit_changed(self, *_args: Any) -> None:
        enabled = self.limit_check.get_active()
        self.limit_spin.set_sensitive(enabled)
        kbps = int(self.limit_spin.get_value()) if enabled else 0
        self.manager.set_download_speed_limit(self.download_id, kbps)

    def _on_complete_action_changed(self, dropdown: Gtk.DropDown, _pspec: Any) -> None:
        idx = dropdown.get_selected()
        if 0 <= idx < len(self._actions):
            action = self._actions[idx]
            self._item.on_complete_action = action
            self.cmd_entry.set_sensitive(action == "command")
            self.manager.db.save_download(self._item)

    def _on_complete_cmd_changed(self, entry: Gtk.Entry) -> None:
        self._item.on_complete_command = entry.get_text()
        self.manager.db.save_download(self._item)

    def _on_pause_resume(self, _btn: Gtk.Button) -> None:
        if self._item.status in (DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING):
            self.manager.pause_download(self.download_id)
        else:
            self.manager.start_download(self.download_id)

    def _on_cancel_or_close(self, _btn: Gtk.Button) -> None:
        if self._item.status != DownloadStatus.COMPLETED:
            self.manager.pause_download(self.download_id)
        self.close()

    def _on_open_file(self, _btn: Gtk.Button) -> None:
        if self._item.filepath.exists():
            subprocess.Popen(["xdg-open", str(self._item.filepath)])

    def _on_open_folder(self, _btn: Gtk.Button) -> None:
        subprocess.Popen(["xdg-open", str(self._item.save_dir)])
