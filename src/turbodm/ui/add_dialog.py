"""IDM-style 'Download File Info' dialog shown before starting a download."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from turbodm.constants import DEFAULT_CATEGORIES  # noqa: E402
from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.core.models import DownloadItem, ProbeResult  # noqa: E402
from turbodm.media.ytdlp_engine import is_streaming_url  # noqa: E402
from turbodm.utils.formatting import (  # noqa: E402
    detect_category,
    filename_from_url,
    format_bytes,
    get_category_folder,
)


class AddDownloadDialog(Adw.Window):
    """IDM 'Download File Info' popup: probes size/resume, selects category, path, and video quality."""

    def __init__(
        self,
        parent: Gtk.Window | None,
        manager: DownloadManager,
        initial_url: str = "",
        initial_filename: str = "",
        referer: str = "",
        cookies: str = "",
        user_agent: str = "",
        headers: dict[str, str] | None = None,
        is_media_hint: bool | None = None,
        on_created: Callable[[DownloadItem, bool], None] | None = None,
    ) -> None:
        super().__init__(
            title="Download File Info — TurboDM",
            transient_for=parent,
            modal=False,
            default_width=580,
            default_height=470,
        )
        self.manager = manager
        self._referer = referer
        self._cookies = cookies
        self._user_agent = user_agent
        self._headers = dict(headers or {})
        self._is_media_hint = is_media_hint
        self._on_created = on_created
        self._probe_result: ProbeResult | None = None
        self._media_formats: list[dict[str, Any]] = []
        self._user_edited_dir = False
        self._user_edited_filename = bool(initial_filename)

        self._categories = list(DEFAULT_CATEGORIES.keys())
        self._queues = list(manager.queues.values())

        self._build_ui()

        if initial_url:
            self.url_entry.set_text(initial_url)
            if initial_filename:
                self.filename_entry.set_text(initial_filename)
            self._apply_url_defaults(initial_url)
            GLib.idle_add(self._trigger_probe)

    def _build_ui(self) -> None:
        toolbar_view = Adw.ToolbarView()
        self.set_content(toolbar_view)

        header = Adw.HeaderBar()
        header.set_title_widget(Adw.WindowTitle(title="Download File Info", subtitle="TurboDM"))
        toolbar_view.add_top_bar(header)

        scroll = Gtk.ScrolledWindow(vexpand=True, hexpand=True)
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        toolbar_view.set_content(scroll)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.set_margin_top(14)
        box.set_margin_bottom(14)
        box.set_margin_start(16)
        box.set_margin_end(16)
        scroll.set_child(box)

        # URL Row + Probe button
        url_label = Gtk.Label(label="URL", xalign=0)
        url_label.add_css_class("heading")
        box.append(url_label)

        url_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.url_entry = Gtk.Entry(hexpand=True, placeholder_text="https://example.com/file.zip or YouTube URL")
        self.url_entry.connect("activate", lambda *_: self._trigger_probe())
        self.url_entry.connect("changed", self._on_url_changed)
        url_row.append(self.url_entry)

        self.probe_btn = Gtk.Button(label="Check Info", tooltip_text="Probe file size, filename, and resume support")
        self.probe_btn.connect("clicked", lambda *_: self._trigger_probe())
        url_row.append(self.probe_btn)
        box.append(url_row)

        # Probe status info banner
        info_card = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=14)
        info_card.add_css_class("card")
        info_card.set_margin_top(2)
        info_card.set_margin_bottom(2)
        info_pad = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=14)
        info_pad.set_margin_top(8)
        info_pad.set_margin_bottom(8)
        info_pad.set_margin_start(12)
        info_pad.set_margin_end(12)
        info_card.append(info_pad)

        self.probe_spinner = Gtk.Spinner()
        info_pad.append(self.probe_spinner)

        self.size_label = Gtk.Label(label="Size: Unknown", xalign=0, hexpand=True)
        self.size_label.add_css_class("mono-value")
        info_pad.append(self.size_label)

        self.resume_label = Gtk.Label(label="Resume: --", xalign=1)
        self.resume_label.add_css_class("mono-value")
        info_pad.append(self.resume_label)
        box.append(info_card)

        # Video Quality Selector (shown only for streaming/video URLs)
        self.media_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.media_box.set_visible(False)
        media_hdr = Gtk.Label(label="Video / Audio Quality (yt-dlp + ffmpeg)", xalign=0)
        media_hdr.add_css_class("heading")
        self.media_box.append(media_hdr)
        self.format_dropdown = Gtk.DropDown.new_from_strings(["Best Available Quality (Auto MP4/MKV)"])
        self.format_dropdown.connect("notify::selected", self._on_format_selected)
        self.media_box.append(self.format_dropdown)
        box.append(self.media_box)

        # Grid for Category, Filename, Save As, Queue, Connections
        grid = Gtk.Grid(column_spacing=12, row_spacing=10)
        box.append(grid)

        # Category
        grid.attach(Gtk.Label(label="Category:", xalign=0), 0, 0, 1, 1)
        self.category_dropdown = Gtk.DropDown.new_from_strings(self._categories)
        self.category_dropdown.set_hexpand(True)
        self.category_dropdown.connect("notify::selected", self._on_category_changed)
        grid.attach(self.category_dropdown, 1, 0, 2, 1)

        # Filename
        grid.attach(Gtk.Label(label="File Name:", xalign=0), 0, 1, 1, 1)
        self.filename_entry = Gtk.Entry(hexpand=True)
        self.filename_entry.connect("changed", self._on_filename_edited)
        grid.attach(self.filename_entry, 1, 1, 2, 1)

        # Save To Directory
        grid.attach(Gtk.Label(label="Save To:", xalign=0), 0, 2, 1, 1)
        self.dir_entry = Gtk.Entry(hexpand=True)
        self.dir_entry.set_text(self.manager.settings.downloads_root)
        self.dir_entry.connect("changed", self._on_dir_edited)
        grid.attach(self.dir_entry, 1, 2, 1, 1)

        browse_btn = Gtk.Button(icon_name="folder-open-symbolic", tooltip_text="Browse folder")
        browse_btn.connect("clicked", self._on_browse_folder)
        grid.attach(browse_btn, 2, 2, 1, 1)

        # Queue & Connections row
        grid.attach(Gtk.Label(label="Queue:", xalign=0), 0, 3, 1, 1)
        queue_names = [q.name for q in self._queues]
        self.queue_dropdown = Gtk.DropDown.new_from_strings(queue_names)
        self.queue_dropdown.set_hexpand(True)
        grid.attach(self.queue_dropdown, 1, 3, 1, 1)

        conn_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        conn_box.append(Gtk.Label(label="Parts:"))
        adj = Gtk.Adjustment(
            value=self.manager.settings.default_connections,
            lower=1,
            upper=32,
            step_increment=1,
            page_increment=4,
        )
        self.conn_spin = Gtk.SpinButton(adjustment=adj, numeric=True)
        conn_box.append(self.conn_spin)
        grid.attach(conn_box, 2, 3, 1, 1)

        # Description
        grid.attach(Gtk.Label(label="Description:", xalign=0), 0, 4, 1, 1)
        self.desc_entry = Gtk.Entry(hexpand=True, placeholder_text="Optional note")
        grid.attach(self.desc_entry, 1, 4, 2, 1)

        # Collapsible Advanced (Auth + Checksum)
        expander = Gtk.Expander(label="Advanced (Authentication & Checksum Verification)")
        adv_grid = Gtk.Grid(column_spacing=10, row_spacing=8)
        adv_grid.set_margin_top(8)
        expander.set_child(adv_grid)

        adv_grid.attach(Gtk.Label(label="Username:", xalign=0), 0, 0, 1, 1)
        self.auth_user_entry = Gtk.Entry(hexpand=True)
        adv_grid.attach(self.auth_user_entry, 1, 0, 1, 1)

        adv_grid.attach(Gtk.Label(label="Password:", xalign=0), 2, 0, 1, 1)
        self.auth_pass_entry = Gtk.PasswordEntry(hexpand=True, show_peek_icon=True)
        adv_grid.attach(self.auth_pass_entry, 3, 0, 1, 1)

        adv_grid.attach(Gtk.Label(label="Checksum:", xalign=0), 0, 1, 1, 1)
        self.checksum_entry = Gtk.Entry(hexpand=True, placeholder_text="Optional SHA-256 or MD5 hash")
        adv_grid.attach(self.checksum_entry, 1, 1, 2, 1)

        self.checksum_algo_dropdown = Gtk.DropDown.new_from_strings(["sha256", "md5"])
        adv_grid.attach(self.checksum_algo_dropdown, 3, 1, 1, 1)
        box.append(expander)

        # Bottom IDM Action Bar: Download Later | Start Download | Cancel
        action_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        action_bar.set_margin_top(10)
        action_bar.set_margin_bottom(12)
        action_bar.set_margin_start(16)
        action_bar.set_margin_end(16)

        cancel_btn = Gtk.Button(label="Cancel")
        cancel_btn.connect("clicked", lambda *_: self.close())
        action_bar.append(cancel_btn)

        spacer = Gtk.Box(hexpand=True)
        action_bar.append(spacer)

        later_btn = Gtk.Button(label="Download Later")
        later_btn.set_tooltip_text("Add to queue without starting immediately")
        later_btn.connect("clicked", lambda *_: self._submit(start_now=False))
        action_bar.append(later_btn)

        start_btn = Gtk.Button(label="Start Download")
        start_btn.add_css_class("suggested-action")
        start_btn.connect("clicked", lambda *_: self._submit(start_now=True))
        action_bar.append(start_btn)

        toolbar_view.add_bottom_bar(action_bar)

    def _on_url_changed(self, _entry: Gtk.Entry) -> None:
        url = self.url_entry.get_text().strip()
        if url.startswith(("http://", "https://", "ftp://")):
            self._apply_url_defaults(url)

    def _apply_url_defaults(self, url: str) -> None:
        is_media = is_streaming_url(url) if self._is_media_hint is None else self._is_media_hint
        if not self._user_edited_filename:
            name = filename_from_url(url)
            self._set_filename_silent(name)
        else:
            name = self.filename_entry.get_text().strip()

        cat = "Video" if is_media else detect_category(name, self.manager.settings.category_extensions)
        if cat in self._categories:
            self.category_dropdown.set_selected(self._categories.index(cat))
        if not self._user_edited_dir:
            folder = get_category_folder(
                cat,
                custom_folders=self.manager.settings.category_folders,
                downloads_root=Path(self.manager.settings.downloads_root),
            )
            self._set_dir_silent(str(folder))

    def _on_category_changed(self, dropdown: Gtk.DropDown, _pspec: Any) -> None:
        idx = dropdown.get_selected()
        if 0 <= idx < len(self._categories) and not self._user_edited_dir:
            cat = self._categories[idx]
            folder = get_category_folder(
                cat,
                custom_folders=self.manager.settings.category_folders,
                downloads_root=Path(self.manager.settings.downloads_root),
            )
            self._set_dir_silent(str(folder))

    def _on_filename_edited(self, _entry: Gtk.Entry) -> None:
        self._user_edited_filename = True

    def _on_dir_edited(self, _entry: Gtk.Entry) -> None:
        self._user_edited_dir = True

    def _set_filename_silent(self, text: str) -> None:
        prev = self._user_edited_filename
        self.filename_entry.set_text(text)
        self._user_edited_filename = prev

    def _set_dir_silent(self, text: str) -> None:
        prev = self._user_edited_dir
        self.dir_entry.set_text(text)
        self._user_edited_dir = prev

    def _on_browse_folder(self, _btn: Gtk.Button) -> None:
        dialog = Gtk.FileDialog(title="Select Save Folder")
        current = Path(self.dir_entry.get_text().strip() or self.manager.settings.downloads_root)
        if current.exists():
            dialog.set_initial_folder(Gio.File.new_for_path(str(current)))

        def _cb(dlg: Gtk.FileDialog, res: Gio.AsyncResult) -> None:
            try:
                folder = dlg.select_folder_finish(res)
                if folder and folder.get_path():
                    self.dir_entry.set_text(folder.get_path())
                    self._user_edited_dir = True
            except GLib.Error:
                pass

        dialog.select_folder(self, None, _cb)

    def _trigger_probe(self) -> bool:
        url = self.url_entry.get_text().strip()
        if not url.startswith(("http://", "https://", "ftp://")):
            return False
        self.probe_spinner.start()
        self.probe_btn.set_sensitive(False)
        self.size_label.set_text("Probing remote file...")
        self.resume_label.set_text("")

        def _done(result: ProbeResult | None, err: Exception | None) -> None:
            GLib.idle_add(self._apply_probe_result, result, err)

        self.manager.probe_async(
            url,
            referer=self._referer,
            cookies=self._cookies,
            user_agent=self._user_agent,
            on_done=_done,
        )
        return False

    def _apply_probe_result(self, result: ProbeResult | None, err: Exception | None) -> bool:
        self.probe_spinner.stop()
        self.probe_btn.set_sensitive(True)

        if err or result is None:
            self.size_label.set_text(f"Probe note: {err or 'Could not fetch metadata'}")
            self.resume_label.set_text("Resume: Unknown")
            return False

        self._probe_result = result
        if result.filename and not self._user_edited_filename:
            self._set_filename_silent(result.filename)

        if result.is_media_page and result.media_formats:
            self._media_formats = result.media_formats
            labels = [f["label"] for f in self._media_formats]
            self.format_dropdown.set_model(Gtk.StringList.new(labels))
            self.format_dropdown.set_selected(0)
            self.media_box.set_visible(True)
            self.size_label.set_text(f"Media: {result.media_title}")
            self.resume_label.set_text("Engine: yt-dlp + ffmpeg")
            if "Video" in self._categories:
                self.category_dropdown.set_selected(self._categories.index("Video"))
        else:
            self.media_box.set_visible(False)
            self.size_label.set_text(f"Size: {format_bytes(result.total_size)}")
            self.resume_label.set_text(f"Resume: {'Yes' if result.accept_ranges else 'No'}")
            cat = detect_category(
                self.filename_entry.get_text().strip() or result.filename,
                self.manager.settings.category_extensions,
            )
            if cat in self._categories:
                self.category_dropdown.set_selected(self._categories.index(cat))

        return False

    def _on_format_selected(self, dropdown: Gtk.DropDown, _pspec: Any) -> None:
        idx = dropdown.get_selected()
        if 0 <= idx < len(self._media_formats):
            fmt = self._media_formats[idx]
            ext = fmt.get("ext", "mp4")
            cur = self.filename_entry.get_text().strip()
            if cur:
                stem = Path(cur).stem
                self._set_filename_silent(f"{stem}.{ext}")
            if fmt.get("audio_only") and "Music" in self._categories:
                self.category_dropdown.set_selected(self._categories.index("Music"))
            elif not fmt.get("audio_only") and "Video" in self._categories:
                self.category_dropdown.set_selected(self._categories.index("Video"))

    def _submit(self, start_now: bool) -> None:
        url = self.url_entry.get_text().strip()
        if not url.startswith(("http://", "https://", "ftp://")):
            self.size_label.set_text("Please enter a valid http:// or https:// URL.")
            return

        cat_idx = self.category_dropdown.get_selected()
        category = self._categories[cat_idx] if 0 <= cat_idx < len(self._categories) else "General"
        q_idx = self.queue_dropdown.get_selected()
        queue_id = self._queues[q_idx].id if 0 <= q_idx < len(self._queues) else "main"

        is_media = bool(self._probe_result and self._probe_result.is_media_page) or is_streaming_url(url)
        fmt_id = ""
        audio_only = False
        if is_media and self._media_formats:
            f_idx = self.format_dropdown.get_selected()
            if 0 <= f_idx < len(self._media_formats):
                fmt_id = str(self._media_formats[f_idx]["format_id"])
                audio_only = bool(self._media_formats[f_idx].get("audio_only", False))

        algo_idx = self.checksum_algo_dropdown.get_selected()
        algo = "md5" if algo_idx == 1 else "sha256"

        item = self.manager.add_download(
            url=url,
            filename=self.filename_entry.get_text().strip(),
            save_dir=self.dir_entry.get_text().strip(),
            category=category,
            queue_id=queue_id,
            description=self.desc_entry.get_text().strip(),
            referer=self._referer,
            cookies=self._cookies,
            user_agent=self._user_agent,
            headers=self._headers,
            auth_user=self.auth_user_entry.get_text().strip(),
            auth_pass=self.auth_pass_entry.get_text(),
            max_connections=int(self.conn_spin.get_value()),
            is_media=is_media,
            media_format_id=fmt_id,
            media_audio_only=audio_only,
            total_size=self._probe_result.total_size if self._probe_result else None,
            resumable=self._probe_result.accept_ranges if self._probe_result else False,
            final_url=self._probe_result.final_url if self._probe_result else "",
            etag=self._probe_result.etag if self._probe_result else "",
            last_modified=self._probe_result.last_modified if self._probe_result else "",
            checksum_expected=self.checksum_entry.get_text().strip(),
            checksum_algo=algo,
            start_immediately=start_now,
        )

        if self._on_created:
            self._on_created(item, start_now)
        self.close()
