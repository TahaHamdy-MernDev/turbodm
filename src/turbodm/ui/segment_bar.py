"""Custom Cairo widget rendering IDM's iconic live multi-segment progress bar."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from turbodm.core.models import DownloadItem, DownloadStatus, SegmentStatus  # noqa: E402


class SegmentProgressBar(Gtk.DrawingArea):
    """Draws exact downloaded byte ranges for every parallel segment in real time."""

    def __init__(self, height: int = 30) -> None:
        super().__init__()
        self._item: DownloadItem | None = None
        self.set_content_height(height)
        self.set_hexpand(True)
        self.add_css_class("segment-bar-frame")
        self.set_draw_func(self._on_draw)

    def update_item(self, item: DownloadItem) -> None:
        self._item = item
        self.queue_draw()

    def _on_draw(self, area: Gtk.DrawingArea, cr, width: int, height: int) -> None:
        if width <= 2 or height <= 2:
            return

        # Background track
        cr.set_source_rgba(0.5, 0.5, 0.55, 0.14)
        cr.rectangle(0, 0, width, height)
        cr.fill()

        item = self._item
        if item is None:
            return

        # Completed state -> full emerald bar
        if item.status == DownloadStatus.COMPLETED:
            cr.set_source_rgba(0.18, 0.76, 0.49, 0.92)
            cr.rectangle(0, 0, width, height)
            cr.fill()
            self._draw_center_text(cr, width, height, "100.0% — Completed")
            return

        total = item.total_size
        segments = list(item.segments)

        if total and total > 0 and segments:
            # Draw each segment's exact byte range [start .. start + downloaded]
            for seg in segments:
                seg_start_x = (seg.start / total) * width
                seg_end_x = ((seg.end + 1) / total) * width if seg.end >= 0 else width
                filled_end_x = ((seg.start + seg.downloaded) / total) * width

                # Subtle segment divider line at start of segment
                if seg.start > 0:
                    cr.set_source_rgba(0.2, 0.2, 0.25, 0.35)
                    cr.set_line_width(1.0)
                    cr.move_to(seg_start_x, 0)
                    cr.line_to(seg_start_x, height)
                    cr.stroke()

                fill_w = max(0.0, min(seg_end_x, filled_end_x) - seg_start_x)
                if fill_w > 0.2:
                    if seg.status == SegmentStatus.COMPLETED:
                        cr.set_source_rgba(0.18, 0.62, 0.90, 0.92)
                    elif seg.status == SegmentStatus.ERROR:
                        cr.set_source_rgba(0.88, 0.22, 0.22, 0.85)
                    elif seg.status == SegmentStatus.PAUSED:
                        cr.set_source_rgba(0.85, 0.62, 0.12, 0.80)
                    else:
                        cr.set_source_rgba(0.21, 0.52, 0.89, 0.92)
                    cr.rectangle(seg_start_x, 0, fill_w, height)
                    cr.fill()

                    # Bright active head indicator for currently receiving segments
                    if seg.status == SegmentStatus.RECEIVING and fill_w > 2.0:
                        tip_x = min(seg_end_x, filled_end_x) - 2.0
                        cr.set_source_rgba(0.42, 0.85, 1.0, 0.95)
                        cr.rectangle(max(seg_start_x, tip_x), 0, 2.5, height)
                        cr.fill()

            pct = item.progress_percent
            label = f"{pct:.1f}% ({len(segments)} parts)"
            self._draw_center_text(cr, width, height, label)
        else:
            # Unknown size streaming progress indicator
            frac = item.progress_fraction
            if frac > 0:
                cr.set_source_rgba(0.21, 0.52, 0.89, 0.9)
                cr.rectangle(0, 0, width * frac, height)
                cr.fill()
            status_label = item.status.value.capitalize()
            self._draw_center_text(cr, width, height, status_label)

    @staticmethod
    def _draw_center_text(cr, width: int, height: int, text: str) -> None:
        cr.select_font_face("Sans", 0, 1)  # Normal slant, bold weight
        cr.set_font_size(12.0)
        extents = cr.text_extents(text)
        x = (width - extents.width) / 2.0
        y = (height + extents.height) / 2.0 - 1.0

        # Subtle dark shadow for contrast on both light/dark themes
        cr.set_source_rgba(0.0, 0.0, 0.0, 0.45)
        cr.move_to(x + 1, y + 1)
        cr.show_text(text)

        cr.set_source_rgba(1.0, 1.0, 1.0, 0.96)
        cr.move_to(x, y)
        cr.show_text(text)
