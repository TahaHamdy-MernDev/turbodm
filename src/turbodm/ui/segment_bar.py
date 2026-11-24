"""Custom Cairo widget rendering TurboDM's signature rounded multi-segment byte map."""

from __future__ import annotations

import math

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from turbodm.core.models import DownloadItem, DownloadStatus, SegmentStatus  # noqa: E402


def _rounded_rect(cr, x: float, y: float, w: float, h: float, r: float) -> None:
    if w <= 0 or h <= 0:
        return
    r = min(r, w / 2.0, h / 2.0)
    cr.new_sub_path()
    cr.arc(x + w - r, y + r, r, -math.pi / 2.0, 0.0)
    cr.arc(x + w - r, y + h - r, r, 0.0, math.pi / 2.0)
    cr.arc(x + r, y + h - r, r, math.pi / 2.0, math.pi)
    cr.arc(x + r, y + r, r, math.pi, 3.0 * math.pi / 2.0)
    cr.close_path()


class SegmentProgressBar(Gtk.DrawingArea):
    """Draws discrete rounded segment cells with Emerald/Adwaita Blue fills and Cyan active tips."""

    def __init__(self, height: int = 34) -> None:
        super().__init__()
        self._item: DownloadItem | None = None
        self.set_content_height(height)
        self.set_hexpand(True)
        self.add_css_class("segment-bar-frame")
        self.set_draw_func(self._on_draw)

    def update_item(self, item: DownloadItem) -> None:
        self._item = item
        self.queue_draw()

    def _on_draw(self, _area: Gtk.DrawingArea, cr, width: int, height: int) -> None:
        if width <= 4 or height <= 4:
            return

        # Recessed #090C10 well background
        cr.set_source_rgb(9 / 255.0, 12 / 255.0, 16 / 255.0)
        _rounded_rect(cr, 0, 0, width, height, 8.0)
        cr.fill()

        pad_x = 6.0
        pad_y = 6.0
        inner_w = max(1.0, width - pad_x * 2.0)
        inner_h = max(1.0, height - pad_y * 2.0)

        item = self._item
        if item is None:
            cr.set_source_rgb(29 / 255.0, 36 / 255.0, 51 / 255.0)
            _rounded_rect(cr, pad_x, pad_y, inner_w, inner_h, 4.0)
            cr.fill()
            return

        # Completed download: render emerald segment cells cleanly
        if item.status == DownloadStatus.COMPLETED:
            cell_count = max(1, min(32, len(item.segments) or 16))
            gap = 3.0 if cell_count > 1 else 0.0
            cell_w = (inner_w - gap * (cell_count - 1)) / cell_count
            cr.set_source_rgb(46 / 255.0, 194 / 255.0, 126 / 255.0)  # #2EC27E
            for i in range(cell_count):
                cx = pad_x + i * (cell_w + gap)
                _rounded_rect(cr, cx, pad_y, max(2.0, cell_w), inner_h, 3.0)
                cr.fill()
            return

        total = item.total_size
        segments = sorted(item.segments, key=lambda s: s.start)

        if total and total > 0 and segments:
            gap = 2.5 if len(segments) > 1 else 0.0
            for idx, seg in enumerate(segments):
                seg_start_x = pad_x + (seg.start / total) * inner_w
                seg_end_x = (
                    pad_x + ((seg.end + 1) / total) * inner_w
                    if seg.end >= 0
                    else (pad_x + inner_w)
                )
                # Leave crisp visual separation between adjacent segments
                cell_x = seg_start_x + (gap / 2.0 if idx > 0 else 0.0)
                cell_right = seg_end_x - (gap / 2.0 if idx < len(segments) - 1 else 0.0)
                cell_w = max(2.0, cell_right - cell_x)

                # Track cell (#1D2433)
                cr.save()
                _rounded_rect(cr, cell_x, pad_y, cell_w, inner_h, 3.0)
                cr.clip()

                cr.set_source_rgb(29 / 255.0, 36 / 255.0, 51 / 255.0)
                cr.rectangle(cell_x, pad_y, cell_w, inner_h)
                cr.fill()

                seg_total = seg.total_bytes if seg.total_bytes > 0 else 1
                frac = min(1.0, max(0.0, seg.downloaded / seg_total))
                fill_w = cell_w * frac

                if fill_w > 0.5:
                    if seg.status == SegmentStatus.COMPLETED or frac >= 0.999:
                        # Emerald Green (#2EC27E)
                        cr.set_source_rgb(46 / 255.0, 194 / 255.0, 126 / 255.0)
                    elif seg.status == SegmentStatus.ERROR:
                        # Error Red (#DE3730)
                        cr.set_source_rgb(222 / 255.0, 55 / 255.0, 48 / 255.0)
                    elif seg.status == SegmentStatus.PAUSED:
                        # Amber Warning (#E5A50A)
                        cr.set_source_rgb(229 / 255.0, 165 / 255.0, 10 / 255.0)
                    else:
                        # Adwaita Blue (#3584E4)
                        cr.set_source_rgb(53 / 255.0, 132 / 255.0, 228 / 255.0)

                    cr.rectangle(cell_x, pad_y, fill_w, inner_h)
                    cr.fill()

                    # Bright Cyan active head tip (#38BDF8) for live receiving workers
                    if seg.status == SegmentStatus.RECEIVING and frac < 0.999:
                        tip_w = min(3.0, fill_w)
                        cr.set_source_rgb(56 / 255.0, 189 / 255.0, 248 / 255.0)
                        cr.rectangle(cell_x + fill_w - tip_w, pad_y, tip_w, inner_h)
                        cr.fill()

                cr.restore()
        else:
            # Unknown size / streaming fallback bar
            cr.save()
            _rounded_rect(cr, pad_x, pad_y, inner_w, inner_h, 4.0)
            cr.clip()
            cr.set_source_rgb(29 / 255.0, 36 / 255.0, 51 / 255.0)
            cr.rectangle(pad_x, pad_y, inner_w, inner_h)
            cr.fill()

            frac = item.progress_fraction
            if frac > 0:
                cr.set_source_rgb(53 / 255.0, 132 / 255.0, 228 / 255.0)
                cr.rectangle(pad_x, pad_y, inner_w * frac, inner_h)
                cr.fill()
                cr.set_source_rgb(56 / 255.0, 189 / 255.0, 248 / 255.0)
                cr.rectangle(max(pad_x, pad_x + inner_w * frac - 3.0), pad_y, 3.0, inner_h)
                cr.fill()
            cr.restore()
