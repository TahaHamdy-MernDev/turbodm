"""IDM-style Batch Download dialog supporting URL wildcard ranges & multi-URL lists."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.utils.batch import parse_url_list  # noqa: E402


class BatchDownloadDialog(Adw.Window):
    """Add multiple URLs at once via wildcard patterns like [01-10] or pasted URL lists."""

    def __init__(
        self,
        parent: Gtk.Window | None,
        manager: DownloadManager,
        initial_urls: list[str] | None = None,
        referer: str = "",
        cookies: str = "",
        user_agent: str = "",
    ) -> None:
        super().__init__(
            title="Batch Download — TurboDM",
            transient_for=parent,
            modal=False,
            default_width=620,
            default_height=490,
        )
        self.manager = manager
        self._referer = referer
        self._cookies = cookies
        self._user_agent = user_agent
        self._queues = list(manager.queues.values())
        self._expanded_urls: list[str] = []
        self._build_ui()

        if initial_urls:
            buf = self.text_view.get_buffer()
            buf.set_text("\n".join(initial_urls))
            self._update_preview()

    def _build_ui(self) -> None:
        toolbar_view = Adw.ToolbarView()
        self.set_content(toolbar_view)

        header = Adw.HeaderBar()
        header.set_title_widget(
            Adw.WindowTitle(title="Batch Download", subtitle="Wildcards [01-10], [a-z] or URL list")
        )
        toolbar_view.add_top_bar(header)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.set_margin_top(12)
        box.set_margin_bottom(12)
        box.set_margin_start(16)
        box.set_margin_end(16)
        toolbar_view.set_content(box)

        hint = Gtk.Label(
            label="Enter one URL per line or use IDM wildcards like https://example.com/part[01-10].zip or [a-d]:",
            xalign=0,
            wrap=True,
        )
        hint.add_css_class("dim-label")
        box.append(hint)

        scroll = Gtk.ScrolledWindow(vexpand=True, min_content_height=180)
        scroll.add_css_class("card")
        self.text_view = Gtk.TextView(monospace=True, wrap_mode=Gtk.WrapMode.NONE)
        self.text_view.get_buffer().connect("changed", lambda *_: self._update_preview())
        scroll.set_child(self.text_view)
        box.append(scroll)

        tools_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        import_btn = Gtk.Button(label="Import from .txt...")
        import_btn.connect("clicked", self._on_import_file)
        tools_row.append(import_btn)

        self.preview_label = Gtk.Label(label="0 valid URLs detected", xalign=1, hexpand=True)
        self.preview_label.add_css_class("mono-value")
        tools_row.append(self.preview_label)
        box.append(tools_row)

        grid = Gtk.Grid(column_spacing=10, row_spacing=8)
        box.append(grid)

        grid.attach(Gtk.Label(label="Queue:", xalign=0), 0, 0, 1, 1)
        self.queue_dropdown = Gtk.DropDown.new_from_strings([q.name for q in self._queues])
        self.queue_dropdown.set_hexpand(True)
        grid.attach(self.queue_dropdown, 1, 0, 2, 1)

        grid.attach(Gtk.Label(label="Save Folder:", xalign=0), 0, 1, 1, 1)
        self.dir_entry = Gtk.Entry(
            hexpand=True, placeholder_text="Leave empty to auto-sort by category"
        )
        grid.attach(self.dir_entry, 1, 1, 1, 1)

        browse_btn = Gtk.Button(icon_name="folder-open-symbolic")
        browse_btn.connect("clicked", self._on_browse)
        grid.attach(browse_btn, 2, 1, 1, 1)

        action_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        action_bar.set_margin_top(8)
        action_bar.set_margin_bottom(12)
        action_bar.set_margin_start(16)
        action_bar.set_margin_end(16)

        cancel_btn = Gtk.Button(label="Cancel")
        cancel_btn.connect("clicked", lambda *_: self.close())
        action_bar.append(cancel_btn)
        action_bar.append(Gtk.Box(hexpand=True))

        queue_btn = Gtk.Button(label="Add to Queue")
        queue_btn.connect("clicked", lambda *_: self._submit(start_queue=False))
        action_bar.append(queue_btn)

        start_btn = Gtk.Button(label="Add & Start Queue")
        start_btn.add_css_class("suggested-action")
        start_btn.connect("clicked", lambda *_: self._submit(start_queue=True))
        action_bar.append(start_btn)

        toolbar_view.add_bottom_bar(action_bar)

    def _update_preview(self) -> None:
        buf = self.text_view.get_buffer()
        text = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
        self._expanded_urls = parse_url_list(text)
        self.preview_label.set_text(f"{len(self._expanded_urls)} URL(s) ready")

    def _on_import_file(self, _btn: Gtk.Button) -> None:
        dialog = Gtk.FileDialog(title="Import URL List (.txt)")

        def _cb(dlg: Gtk.FileDialog, res: Gio.AsyncResult) -> None:
            try:
                f = dlg.open_finish(res)
                if f and f.get_path():
                    content = Path(f.get_path()).read_text(encoding="utf-8", errors="replace")
                    self.text_view.get_buffer().set_text(content)
                    self._update_preview()
            except (GLib.Error, OSError):
                pass

        dialog.open(self, None, _cb)

    def _on_browse(self, _btn: Gtk.Button) -> None:
        dialog = Gtk.FileDialog(title="Select Destination Folder")

        def _cb(dlg: Gtk.FileDialog, res: Gio.AsyncResult) -> None:
            try:
                folder = dlg.select_folder_finish(res)
                if folder and folder.get_path():
                    self.dir_entry.set_text(folder.get_path())
            except GLib.Error:
                pass

        dialog.select_folder(self, None, _cb)

    def _submit(self, start_queue: bool) -> None:
        self._update_preview()
        if not self._expanded_urls:
            return
        q_idx = self.queue_dropdown.get_selected()
        queue_id = self._queues[q_idx].id if 0 <= q_idx < len(self._queues) else "main"
        save_dir = self.dir_entry.get_text().strip()

        for url in self._expanded_urls:
            self.manager.add_download(
                url=url,
                save_dir=save_dir,
                queue_id=queue_id,
                referer=self._referer,
                cookies=self._cookies,
                user_agent=self._user_agent,
                start_immediately=False,
            )
        if start_queue:
            self.manager.start_queue(queue_id)
        self.close()
