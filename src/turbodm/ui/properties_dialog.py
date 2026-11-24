"""Download Properties & Checksum Verification dialog."""

from __future__ import annotations

import datetime
import threading

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.core.models import DownloadItem  # noqa: E402
from turbodm.utils.formatting import compute_file_hash, format_bytes  # noqa: E402


class PropertiesDialog(Adw.Window):
    """Inspect & verify a download item's metadata, headers, path, and SHA-256/MD5 checksum."""

    def __init__(self, parent: Gtk.Window | None, manager: DownloadManager, item: DownloadItem) -> None:
        super().__init__(
            title=f"Properties — {item.filename}",
            transient_for=parent,
            modal=False,
            default_width=540,
            default_height=430,
        )
        self.manager = manager
        self.item = item
        self._build_ui()

    def _build_ui(self) -> None:
        tv = Adw.ToolbarView()
        self.set_content(tv)
        header = Adw.HeaderBar()
        header.set_title_widget(Adw.WindowTitle(title="Download Properties", subtitle=self.item.filename))
        tv.add_top_bar(header)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.set_margin_top(14)
        box.set_margin_bottom(14)
        box.set_margin_start(16)
        box.set_margin_end(16)
        tv.set_content(box)

        grid = Gtk.Grid(column_spacing=12, row_spacing=8)
        box.append(grid)

        created_str = datetime.datetime.fromtimestamp(self.item.created_at).strftime("%Y-%m-%d %H:%M:%S")
        fields = [
            ("File Name:", self.item.filename),
            ("Category:", self.item.category),
            ("Status:", self.item.status.value.capitalize()),
            ("Size:", format_bytes(self.item.total_size)),
            ("Downloaded:", format_bytes(self.item.downloaded_size)),
            ("Saved Path:", str(self.item.filepath)),
            ("URL:", self.item.url),
            ("Referer:", self.item.referer or "--"),
            ("Added On:", created_str),
            ("Description:", self.item.description or "--"),
        ]
        for idx, (k, v) in enumerate(fields):
            kl = Gtk.Label(label=k, xalign=0)
            kl.add_css_class("dim-label")
            vl = Gtk.Label(label=v, xalign=0, hexpand=True, selectable=True, wrap=True)
            vl.add_css_class("mono-value")
            grid.attach(kl, 0, idx, 1, 1)
            grid.attach(vl, 1, idx, 1, 1)

        box.append(Gtk.Separator())

        hash_title = Gtk.Label(label="File Integrity / Checksum", xalign=0)
        hash_title.add_css_class("heading")
        box.append(hash_title)

        hash_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.algo_dropdown = Gtk.DropDown.new_from_strings(["sha256", "md5"])
        hash_row.append(self.algo_dropdown)
        self.calc_btn = Gtk.Button(label="Compute Hash")
        self.calc_btn.set_sensitive(self.item.filepath.exists())
        self.calc_btn.connect("clicked", self._on_compute_hash)
        hash_row.append(self.calc_btn)
        box.append(hash_row)

        self.hash_result = Gtk.Entry(editable=False, placeholder_text="Click 'Compute Hash' to verify file")
        self.hash_result.add_css_class("mono-value")
        box.append(self.hash_result)

        close_btn = Gtk.Button(label="Close", halign=Gtk.Align.END)
        close_btn.connect("clicked", lambda *_: self.close())
        box.append(close_btn)

    def _on_compute_hash(self, _btn: Gtk.Button) -> None:
        algo = "md5" if self.algo_dropdown.get_selected() == 1 else "sha256"
        self.calc_btn.set_sensitive(False)
        self.hash_result.set_text("Computing...")

        def _worker() -> None:
            try:
                digest = compute_file_hash(self.item.filepath, algo)
            except OSError as exc:
                digest = f"Error: {exc}"
            GLib.idle_add(self._finish_hash, digest)

        threading.Thread(target=_worker, daemon=True).start()

    def _finish_hash(self, digest: str) -> bool:
        self.hash_result.set_text(digest)
        self.calc_btn.set_sensitive(True)
        return False
