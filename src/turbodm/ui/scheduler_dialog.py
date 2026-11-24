"""IDM-style Scheduler & Queue Manager dialog."""

from __future__ import annotations

import uuid
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from turbodm.core.manager import DownloadManager  # noqa: E402
from turbodm.core.models import QueueConfig  # noqa: E402


class SchedulerDialog(Adw.Window):
    """Configure download queues, simultaneous limits, schedule times, and post-queue actions."""

    def __init__(self, parent: Gtk.Window | None, manager: DownloadManager) -> None:
        super().__init__(
            title="Scheduler & Queues — TurboDM",
            transient_for=parent,
            modal=False,
            default_width=660,
            default_height=460,
        )
        self.manager = manager
        self._selected_queue_id: str = "main"
        self._loading = False
        self._day_checks: list[Gtk.ToggleButton] = []
        self._build_ui()
        self._reload_queue_list()

    def _build_ui(self) -> None:
        toolbar_view = Adw.ToolbarView()
        self.set_content(toolbar_view)

        header = Adw.HeaderBar()
        header.set_title_widget(Adw.WindowTitle(title="Scheduler & Queues", subtitle="TurboDM"))
        toolbar_view.add_top_bar(header)

        paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL, position=210)
        toolbar_view.set_content(paned)

        # Left pane: Queue list + Add/Delete
        left = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        left.set_margin_top(10)
        left.set_margin_bottom(10)
        left.set_margin_start(10)
        left.set_margin_end(6)

        scroll = Gtk.ScrolledWindow(vexpand=True)
        self.queue_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.queue_list.add_css_class("navigation-sidebar")
        self.queue_list.connect("row-selected", self._on_queue_selected)
        scroll.set_child(self.queue_list)
        left.append(scroll)

        q_btns = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        add_btn = Gtk.Button(label="New Queue", hexpand=True)
        add_btn.connect("clicked", self._on_new_queue)
        self.del_q_btn = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text="Delete queue")
        self.del_q_btn.connect("clicked", self._on_delete_queue)
        q_btns.append(add_btn)
        q_btns.append(self.del_q_btn)
        left.append(q_btns)
        paned.set_start_child(left)

        # Right pane: Selected queue settings
        right_scroll = Gtk.ScrolledWindow(vexpand=True, hexpand=True)
        right = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        right.set_margin_top(14)
        right.set_margin_bottom(14)
        right.set_margin_start(16)
        right.set_margin_end(16)
        right_scroll.set_child(right)
        paned.set_end_child(right_scroll)

        self.q_name_entry = Gtk.Entry()
        name_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        name_row.append(Gtk.Label(label="Queue Name:", width_chars=14, xalign=0))
        self.q_name_entry.set_hexpand(True)
        name_row.append(self.q_name_entry)
        right.append(name_row)

        conc_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        conc_row.append(Gtk.Label(label="Simultaneous:", width_chars=14, xalign=0))
        self.max_conc_spin = Gtk.SpinButton(
            adjustment=Gtk.Adjustment(value=3, lower=1, upper=20, step_increment=1)
        )
        conc_row.append(self.max_conc_spin)
        conc_row.append(Gtk.Label(label="files at the same time"))
        right.append(conc_row)

        right.append(Gtk.Separator())

        self.sched_check = Gtk.CheckButton(label="Enable Scheduled Start / Stop")
        self.sched_check.connect("toggled", self._update_schedule_sensitivity)
        right.append(self.sched_check)

        time_grid = Gtk.Grid(column_spacing=12, row_spacing=8)
        time_grid.attach(Gtk.Label(label="Start time (HH:MM):", xalign=0), 0, 0, 1, 1)
        self.start_time_entry = Gtk.Entry(text="02:00", width_chars=8)
        time_grid.attach(self.start_time_entry, 1, 0, 1, 1)

        self.stop_time_check = Gtk.CheckButton(label="Stop at (HH:MM):")
        time_grid.attach(self.stop_time_check, 0, 1, 1, 1)
        self.stop_time_entry = Gtk.Entry(text="06:00", width_chars=8)
        time_grid.attach(self.stop_time_entry, 1, 1, 1, 1)
        right.append(time_grid)

        days_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
        for day_name in ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"):
            tb = Gtk.ToggleButton(label=day_name, active=True)
            self._day_checks.append(tb)
            days_box.append(tb)
        right.append(days_box)

        right.append(Gtk.Separator())

        post_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        post_row.append(Gtk.Label(label="When queue finishes:", xalign=0))
        self._post_actions = ["notify", "command", "suspend", "shutdown"]
        self.post_dropdown = Gtk.DropDown.new_from_strings(
            ["Show notification", "Run shell command", "Suspend computer", "Shut down computer"]
        )
        post_row.append(self.post_dropdown)
        right.append(post_row)

        self.post_cmd_entry = Gtk.Entry(placeholder_text="Custom command on queue completion")
        right.append(self.post_cmd_entry)

        # Footer buttons
        footer = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        footer.set_margin_top(8)
        footer.set_margin_bottom(12)
        footer.set_margin_start(14)
        footer.set_margin_end(14)

        self.start_q_btn = Gtk.Button(label="Start Now")
        self.start_q_btn.add_css_class("suggested-action")
        self.start_q_btn.connect("clicked", self._on_start_queue)
        footer.append(self.start_q_btn)

        self.stop_q_btn = Gtk.Button(label="Stop Queue")
        self.stop_q_btn.connect("clicked", self._on_stop_queue)
        footer.append(self.stop_q_btn)

        footer.append(Gtk.Box(hexpand=True))

        save_btn = Gtk.Button(label="Apply")
        save_btn.connect("clicked", lambda *_: self._save_current())
        footer.append(save_btn)

        close_btn = Gtk.Button(label="Close")
        close_btn.connect("clicked", lambda *_: (self._save_current(), self.close()))
        footer.append(close_btn)

        toolbar_view.add_bottom_bar(footer)

    def _reload_queue_list(self) -> None:
        while (child := self.queue_list.get_first_child()) is not None:
            self.queue_list.remove(child)
        selected_row = None
        for q in self.manager.queues.values():
            row = Gtk.ListBoxRow()
            row.queue_id = q.id  # type: ignore[attr-defined]
            lbl = Gtk.Label(
                label=f"{q.name}{' (Running)' if q.is_running else ''}",
                xalign=0,
            )
            lbl.set_margin_top(6)
            lbl.set_margin_bottom(6)
            lbl.set_margin_start(8)
            row.set_child(lbl)
            self.queue_list.append(row)
            if q.id == self._selected_queue_id:
                selected_row = row
        if selected_row:
            self.queue_list.select_row(selected_row)

    def _on_queue_selected(self, _lb: Gtk.ListBox, row: Gtk.ListBoxRow | None) -> None:
        if row is None:
            return
        self._selected_queue_id = row.queue_id  # type: ignore[attr-defined]
        q = self.manager.queues.get(self._selected_queue_id)
        if not q:
            return
        self._loading = True
        self.q_name_entry.set_text(q.name)
        self.q_name_entry.set_sensitive(q.id not in ("main", "sync"))
        self.del_q_btn.set_sensitive(q.id not in ("main", "sync"))
        self.max_conc_spin.set_value(q.max_concurrent)
        self.sched_check.set_active(q.schedule_enabled)
        self.start_time_entry.set_text(q.start_time)
        self.stop_time_check.set_active(q.stop_time_enabled)
        self.stop_time_entry.set_text(q.stop_time)
        for idx, tb in enumerate(self._day_checks):
            tb.set_active(idx in q.days_of_week)
        if q.on_complete_action in self._post_actions:
            self.post_dropdown.set_selected(self._post_actions.index(q.on_complete_action))
        self.post_cmd_entry.set_text(q.on_complete_command)
        self._loading = False
        self._update_schedule_sensitivity()

    def _update_schedule_sensitivity(self, *_args: Any) -> None:
        en = self.sched_check.get_active()
        self.start_time_entry.set_sensitive(en)
        self.stop_time_check.set_sensitive(en)
        self.stop_time_entry.set_sensitive(en)
        for tb in self._day_checks:
            tb.set_sensitive(en)

    def _save_current(self) -> None:
        if self._loading:
            return
        q = self.manager.queues.get(self._selected_queue_id)
        if not q:
            return
        if q.id not in ("main", "sync") and self.q_name_entry.get_text().strip():
            q.name = self.q_name_entry.get_text().strip()
        q.max_concurrent = int(self.max_conc_spin.get_value())
        q.schedule_enabled = self.sched_check.get_active()
        q.start_time = self.start_time_entry.get_text().strip() or "02:00"
        q.stop_time_enabled = self.stop_time_check.get_active()
        q.stop_time = self.stop_time_entry.get_text().strip() or "06:00"
        q.days_of_week = [idx for idx, tb in enumerate(self._day_checks) if tb.get_active()]
        p_idx = self.post_dropdown.get_selected()
        q.on_complete_action = self._post_actions[p_idx] if 0 <= p_idx < len(self._post_actions) else "notify"
        q.on_complete_command = self.post_cmd_entry.get_text().strip()
        self.manager.save_queue(q)
        self._reload_queue_list()

    def _on_new_queue(self, _btn: Gtk.Button) -> None:
        new_id = f"q_{uuid.uuid4().hex[:6]}"
        count = len(self.manager.queues) + 1
        q = QueueConfig(id=new_id, name=f"Custom Queue {count}", max_concurrent=2)
        self.manager.save_queue(q)
        self._selected_queue_id = new_id
        self._reload_queue_list()

    def _on_delete_queue(self, _btn: Gtk.Button) -> None:
        if self._selected_queue_id in ("main", "sync"):
            return
        self.manager.delete_queue(self._selected_queue_id)
        self._selected_queue_id = "main"
        self._reload_queue_list()

    def _on_start_queue(self, _btn: Gtk.Button) -> None:
        self._save_current()
        self.manager.start_queue(self._selected_queue_id)
        self._reload_queue_list()

    def _on_stop_queue(self, _btn: Gtk.Button) -> None:
        self.manager.stop_queue(self._selected_queue_id)
        self._reload_queue_list()
