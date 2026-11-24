"""TurboDM floating 'Download Progress' window styled 1:1 with the website Hero telemetry dialog."""

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
from turbodm.ui.widgets import CompactStepper  # noqa: E402
from turbodm.utils.formatting import format_bytes, format_eta, format_speed  # noqa: E402


class DownloadProgressWindow(Adw.Window):
    """Per-download floating progress dialog matching the TurboDM website telemetry card."""

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
            default_width=590,
            default_height=460,
        )
        self.manager = manager
        self.download_id = item.id
        self._item = item
        self._auto_close_on_complete = False
        self._details_visible = True
        self._seg_rows: dict[int, tuple[Gtk.Label, Gtk.Label, Gtk.Label, Gtk.Label]] = {}

        self._build_ui()
        self.refresh(item)

    def _build_ui(self) -> None:
        toolbar_view = Adw.ToolbarView()
        self.set_content(toolbar_view)

        # HeaderBar with live emerald dot + Download Progress + filename + emerald % badge
        header = Adw.HeaderBar()
        header.set_show_title(False)

        title_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8, valign=Gtk.Align.CENTER)
        self.status_dot = Gtk.Box(valign=Gtk.Align.CENTER)
        self.status_dot.add_css_class("dot-emerald")
        title_row.append(self.status_dot)

        hdr_lbl = Gtk.Label(label="Download Progress")
        hdr_lbl.add_css_class("brand-title")
        title_row.append(hdr_lbl)

        self.hdr_file_lbl = Gtk.Label(
            label=self._item.filename,
            ellipsize=Pango.EllipsizeMode.END,
            max_width_chars=28,
        )
        self.hdr_file_lbl.add_css_class("mono-value")
        self.hdr_file_lbl.add_css_class("text-muted")
        title_row.append(self.hdr_file_lbl)
        header.pack_start(title_row)

        self.hdr_pct_lbl = Gtk.Label(label="0.0%")
        self.hdr_pct_lbl.add_css_class("mono-value")
        self.hdr_pct_lbl.add_css_class("text-emerald")
        self.hdr_pct_lbl.set_margin_end(6)
        header.pack_end(self.hdr_pct_lbl)
        toolbar_view.add_top_bar(header)

        main_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        main_box.set_margin_top(10)
        main_box.set_margin_bottom(8)
        main_box.set_margin_start(14)
        main_box.set_margin_end(14)
        toolbar_view.set_content(main_box)

        notebook = Gtk.Notebook()
        notebook.set_vexpand(True)
        main_box.append(notebook)

        # --- Tab 1: Download Status ---
        status_tab = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        status_tab.set_margin_top(12)
        status_tab.set_margin_bottom(12)
        status_tab.set_margin_start(12)
        status_tab.set_margin_end(12)

        # URL line
        self.url_label = Gtk.Label(xalign=0, ellipsize=Pango.EllipsizeMode.MIDDLE, selectable=True)
        self.url_label.add_css_class("mono-value")
        self.url_label.add_css_class("text-muted")
        status_tab.append(self.url_label)

        # 3-Column Recessed Telemetry Strip (matches website Hero dialog 1:1)
        telemetry = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=16, homogeneous=True)
        telemetry.add_css_class("telemetry-strip")

        def _metric(title: str, val_cls: str) -> Gtk.Label:
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            t = Gtk.Label(label=title, xalign=0)
            t.add_css_class("download-meta")
            v = Gtk.Label(label="--", xalign=0)
            v.add_css_class("mono-lg")
            if val_cls:
                v.add_css_class(val_cls)
            col.append(t)
            col.append(v)
            telemetry.append(col)
            return v

        self.lbl_speed = _metric("Transfer Rate", "text-emerald")
        self.lbl_threads = _metric("Threads", "")
        self.lbl_resume = _metric("Resume Capability", "text-blue")
        status_tab.append(telemetry)

        # Secondary compact telemetry line (Downloaded / Size / ETA / Status)
        sub_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=14)
        self.lbl_downloaded = Gtk.Label(xalign=0, hexpand=True)
        self.lbl_downloaded.add_css_class("mono-value")
        self.lbl_eta = Gtk.Label(xalign=1)
        self.lbl_eta.add_css_class("mono-value")
        self.lbl_eta.add_css_class("text-muted")
        sub_row.append(self.lbl_downloaded)
        sub_row.append(self.lbl_eta)
        status_tab.append(sub_row)

        # Signature Multi-Segment Byte Map
        seg_hdr = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.lbl_seg_title = Gtk.Label(label="Segment Byte Map", xalign=0, hexpand=True)
        self.lbl_seg_title.add_css_class("download-meta")
        self.lbl_status = Gtk.Label(label="Dynamic Split Active", xalign=1)
        self.lbl_status.add_css_class("mono-value")
        self.lbl_status.add_css_class("text-cyan")
        seg_hdr.append(self.lbl_seg_title)
        seg_hdr.append(self.lbl_status)
        status_tab.append(seg_hdr)

        self.segment_bar = SegmentProgressBar(height=34)
        status_tab.append(self.segment_bar)

        # Collapsible Per-Connection Recessed Table (Seg | Downloaded | Speed | State)
        self.details_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0, vexpand=True)
        self.details_box.add_css_class("recessed-well")

        tbl_hdr = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        tbl_hdr.set_margin_top(6)
        tbl_hdr.set_margin_bottom(6)
        tbl_hdr.set_margin_start(10)
        tbl_hdr.set_margin_end(10)
        for text, chars, expand in (("Seg", 5, False), ("Downloaded", 18, False), ("Speed", 12, False), ("State", 12, True)):
            l = Gtk.Label(label=text, xalign=0, width_chars=chars, hexpand=expand)
            l.add_css_class("mono-value")
            l.add_css_class("text-muted")
            tbl_hdr.append(l)
        self.details_box.append(tbl_hdr)
        self.details_box.append(Gtk.Separator())

        seg_scroll = Gtk.ScrolledWindow(min_content_height=115, vexpand=True)
        self.seg_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.seg_list.add_css_class("download-listbox")
        seg_scroll.set_child(self.seg_list)
        self.details_box.append(seg_scroll)
        status_tab.append(self.details_box)

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

        limit_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10, valign=Gtk.Align.CENTER)
        limit_row.append(Gtk.Label(label="Maximum download speed (KB/s):"))
        self.limit_spin = CompactStepper(
            value=self._item.speed_limit_kbps or 512,
            lower=10,
            upper=500000,
            step=64,
            width_chars=6,
            on_changed=lambda _v: self._on_speed_limit_changed(),
        )
        self.limit_spin.set_sensitive(self._item.speed_limit_kbps > 0)
        limit_row.append(self.limit_spin)
        limiter_tab.append(limit_row)

        hint = Gtk.Label(
            label="Tip: Speed changes apply immediately without restarting active connections.",
            xalign=0,
            wrap=True,
        )
        hint.add_css_class("download-meta")
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
        footer.set_margin_bottom(12)
        footer.set_margin_start(14)
        footer.set_margin_end(14)

        self.details_btn = Gtk.Button(label="Hide details")
        self.details_btn.add_css_class("idm-btn-ghost")
        self.details_btn.connect("clicked", self._toggle_details)
        footer.append(self.details_btn)

        footer.append(Gtk.Box(hexpand=True))

        self.open_folder_btn = Gtk.Button(label="Open Folder")
        self.open_folder_btn.add_css_class("idm-btn-chip")
        self.open_folder_btn.set_visible(False)
        self.open_folder_btn.connect("clicked", self._on_open_folder)
        footer.append(self.open_folder_btn)

        self.open_file_btn = Gtk.Button(label="Open File")
        self.open_file_btn.add_css_class("idm-btn-primary")
        self.open_file_btn.set_visible(False)
        self.open_file_btn.connect("clicked", self._on_open_file)
        footer.append(self.open_file_btn)

        self.pause_btn = Gtk.Button(label="Pause")
        self.pause_btn.add_css_class("idm-btn-chip")
        self.pause_btn.connect("clicked", self._on_pause_resume)
        footer.append(self.pause_btn)

        self.cancel_btn = Gtk.Button(label="Cancel")
        self.cancel_btn.add_css_class("idm-btn-chip")
        self.cancel_btn.connect("clicked", self._on_cancel_or_close)
        footer.append(self.cancel_btn)

        toolbar_view.add_bottom_bar(footer)

    def refresh(self, item: DownloadItem) -> None:
        self._item = item
        pct = item.progress_percent
        self.set_title(f"{pct:.1f}% — {item.filename} — TurboDM")
        self.hdr_file_lbl.set_text(item.filename)
        self.hdr_pct_lbl.set_text(f"{pct:.1f}%")

        for cls in ("dot-emerald", "dot-amber", "dot-muted"):
            self.status_dot.remove_css_class(cls)
        if item.status in (DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED):
            self.status_dot.add_css_class("dot-emerald")
        elif item.status == DownloadStatus.PAUSED:
            self.status_dot.add_css_class("dot-amber")
        else:
            self.status_dot.add_css_class("dot-muted")

        self.url_label.set_text(item.url)
        seg_count = max(1, len(item.segments) or item.max_connections)
        self.lbl_speed.set_text(format_speed(item.speed) if item.status != DownloadStatus.COMPLETED else "Completed")
        self.lbl_threads.set_text(f"{seg_count} connection{'s' if seg_count != 1 else ''}")
        self.lbl_resume.set_text(f"Resume: {'Yes' if item.resumable else 'No'}")

        self.lbl_downloaded.set_text(
            f"Downloaded: {format_bytes(item.downloaded_size)} / {format_bytes(item.total_size)}"
        )
        self.lbl_eta.set_text(f"ETA: {format_eta(item.eta)}" if item.eta else "ETA: --")
        self.lbl_seg_title.set_text(f"{seg_count}-Segment Byte Map")

        if item.status == DownloadStatus.DOWNLOADING:
            self.lbl_status.set_text("Dynamic Split Active" if item.resumable else "Single Stream")
        elif item.status == DownloadStatus.COMPLETED:
            self.lbl_status.set_text("All Segments Merged (0 MB Copy)")
        elif item.status == DownloadStatus.ERROR and item.error_message:
            self.lbl_status.set_text(f"Error: {item.error_message[:48]}")
        else:
            self.lbl_status.set_text(item.status.value.capitalize())

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
        for seg in list(item.segments):
            if seg.index not in self._seg_rows:
                row_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
                row_box.set_margin_top(4)
                row_box.set_margin_bottom(4)
                row_box.set_margin_start(10)
                row_box.set_margin_end(10)

                lbl_n = Gtk.Label(label=f"#{seg.index + 1:02d}", width_chars=5, xalign=0)
                lbl_n.add_css_class("mono-value")
                lbl_dl = Gtk.Label(width_chars=18, xalign=0)
                lbl_dl.add_css_class("mono-value")
                lbl_dl.add_css_class("text-muted")
                lbl_spd = Gtk.Label(width_chars=12, xalign=0)
                lbl_spd.add_css_class("mono-value")
                lbl_state = Gtk.Label(xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END)
                lbl_state.add_css_class("mono-value")

                row_box.append(lbl_n)
                row_box.append(lbl_dl)
                row_box.append(lbl_spd)
                row_box.append(lbl_state)
                self.seg_list.append(row_box)
                self._seg_rows[seg.index] = (lbl_n, lbl_dl, lbl_spd, lbl_state)

            _, lbl_dl, lbl_spd, lbl_state = self._seg_rows[seg.index]
            if seg.total_bytes > 0:
                lbl_dl.set_text(f"{format_bytes(seg.downloaded)} / {format_bytes(seg.total_bytes)}")
            else:
                lbl_dl.set_text(format_bytes(seg.downloaded))

            for cls in ("text-emerald", "text-blue", "text-cyan", "text-amber", "text-error", "text-muted"):
                lbl_spd.remove_css_class(cls)
                lbl_state.remove_css_class(cls)

            if seg.status == SegmentStatus.RECEIVING:
                lbl_spd.set_text(format_speed(seg.speed))
                lbl_spd.add_css_class("text-cyan")
                lbl_state.set_text("Receiving")
                lbl_state.add_css_class("text-blue")
            elif seg.status == SegmentStatus.COMPLETED:
                lbl_spd.set_text("—")
                lbl_spd.add_css_class("text-muted")
                lbl_state.set_text("Done")
                lbl_state.add_css_class("text-emerald")
            elif seg.status == SegmentStatus.CONNECTING:
                lbl_spd.set_text("—")
                lbl_spd.add_css_class("text-muted")
                lbl_state.set_text("Connecting")
                lbl_state.add_css_class("text-cyan")
            elif seg.status == SegmentStatus.PAUSED:
                lbl_spd.set_text("0 KB/s")
                lbl_spd.add_css_class("text-muted")
                lbl_state.set_text("Paused")
                lbl_state.add_css_class("text-amber")
            elif seg.status == SegmentStatus.ERROR:
                lbl_spd.set_text("—")
                lbl_spd.add_css_class("text-muted")
                lbl_state.set_text(f"Error: {seg.error[:24]}")
                lbl_state.add_css_class("text-error")
            else:
                lbl_spd.set_text("—")
                lbl_spd.add_css_class("text-muted")
                lbl_state.set_text(seg.status.value.capitalize())
                lbl_state.add_css_class("text-muted")

    def _toggle_details(self, _btn: Gtk.Button) -> None:
        self._details_visible = not self._details_visible
        self.details_box.set_visible(self._details_visible)
        self.details_btn.set_label("Hide details" if self._details_visible else "Show details")

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
