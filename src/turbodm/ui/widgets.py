"""Reusable compact UI widgets for TurboDM."""

from __future__ import annotations

from collections.abc import Callable

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402


class CompactStepper(Gtk.Box):
    """Sleek compact numeric stepper: [ - | centered value | + ] with tight proportions."""

    def __init__(
        self,
        value: int = 0,
        lower: int = 0,
        upper: int = 100,
        step: int = 1,
        width_chars: int = 5,
        on_changed: Callable[[int], None] | None = None,
    ) -> None:
        super().__init__(
            orientation=Gtk.Orientation.HORIZONTAL,
            spacing=0,
            valign=Gtk.Align.CENTER,
            halign=Gtk.Align.END,
        )
        self._lower = int(lower)
        self._upper = int(upper)
        self._step = max(1, int(step))
        self._value = self._clamp(int(value))
        self._on_changed = on_changed
        self._syncing = False

        self.add_css_class("compact-stepper")

        self.btn_dec = Gtk.Button(label="−")
        self.btn_dec.add_css_class("stepper-btn")
        self.btn_dec.add_css_class("stepper-btn-left")
        self.btn_dec.set_can_focus(False)
        self.btn_dec.connect("clicked", lambda *_: self.set_value(self._value - self._step, notify=True))

        self.entry = Gtk.Entry(
            text=str(self._value),
            xalign=0.5,
            width_chars=width_chars,
            max_width_chars=max(width_chars + 2, 7),
        )
        self.entry.set_input_purpose(Gtk.InputPurpose.DIGITS)
        self.entry.add_css_class("stepper-entry")
        self.entry.connect("activate", lambda *_: self._commit_entry())

        focus_ctrl = Gtk.EventControllerFocus()
        focus_ctrl.connect("leave", lambda *_: self._commit_entry())
        self.entry.add_controller(focus_ctrl)

        self.btn_inc = Gtk.Button(label="+")
        self.btn_inc.add_css_class("stepper-btn")
        self.btn_inc.add_css_class("stepper-btn-right")
        self.btn_inc.set_can_focus(False)
        self.btn_inc.connect("clicked", lambda *_: self.set_value(self._value + self._step, notify=True))

        self.append(self.btn_dec)
        self.append(self.entry)
        self.append(self.btn_inc)
        self._update_buttons()

    def _clamp(self, val: int) -> int:
        return max(self._lower, min(self._upper, val))

    def _update_buttons(self) -> None:
        self.btn_dec.set_sensitive(self._value > self._lower)
        self.btn_inc.set_sensitive(self._value < self._upper)

    def _commit_entry(self) -> None:
        if self._syncing:
            return
        raw = self.entry.get_text().strip()
        try:
            parsed = int(float(raw)) if raw else self._value
        except ValueError:
            parsed = self._value
        self.set_value(parsed, notify=True)

    def get_value(self) -> int:
        self._commit_entry()
        return self._value

    def set_value(self, val: int, notify: bool = False) -> None:
        clamped = self._clamp(int(val))
        changed = clamped != self._value
        self._value = clamped
        self._syncing = True
        self.entry.set_text(str(self._value))
        self._syncing = False
        self._update_buttons()
        if notify and changed and self._on_changed:
            self._on_changed(self._value)
