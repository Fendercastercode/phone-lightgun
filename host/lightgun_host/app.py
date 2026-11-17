"""Host entry point: control window + overlay + UDP receiver + injector."""
from __future__ import annotations

import argparse
import logging
import math
import os
import socket
import sys
import time

from PySide6.QtCore import QObject, Qt, QTimer, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QGroupBox, QMessageBox, QDoubleSpinBox, QFormLayout, QHBoxLayout,
                               QLabel, QPushButton, QPlainTextEdit, QVBoxLayout, QWidget)

from . import protocol as P
from .calibration import Calibrator
from .inject import Injector, NullBackend, make_backend
from .inject.gamepad import NullGamepad, make_gamepad
from .presets import PRESETS, apply_preset, button_labels, player_buttons
from .autopreset import foreground_exe, foreground_title, preset_for
from . import bindread
from . import emucheck, emufix, emulocate
from . import theme
from .net import Receiver, local_ips
from .overlay import Overlay
from .store import Store, config_dir

log = logging.getLogger("lightgun")


class Bridge(QObject):
    ctrl = Signal(int, dict)
    trigger = Signal(int, float, float, bool)
    press = Signal(int, str, str)


class QtLogHandler(logging.Handler):
    def __init__(self, sink):
        super().__init__()
        self.sink = sink

    def emit(self, record):
        self.sink(self.format(record))


class HostWindow(QWidget):
    logline = Signal(str)

    def __init__(self, args):
        super().__init__()
        self.setWindowTitle("RetroSight — PC Host")
        # --dry-run (testing) must never touch the real settings (monitor, key, screen sizes)
        self.store = Store(config_dir() / "config-dryrun.json") if args.dry_run else Store()
        if args.new_key:
            self.store.regenerate_key()
        self.backend = NullBackend() if args.dry_run else make_backend()
        from . import inject as _inject
        self.backend_problem = None if args.dry_run else _inject.BACKEND_PROBLEM
        self.monitors = self.backend.monitors()
        self.overlay = Overlay()
        self.bridge = Bridge()
        self.bridge.ctrl.connect(self._on_ctrl)
        self.bridge.trigger.connect(self._on_trigger)
        self.bridge.press.connect(self._on_press)
        self._emu = None            # last emulator status (kept while another window is in front)
        self._emu_t = 0.0
        self._echo = None           # (time, button, action) of the last button press
        self._cursor_bad = 0        # consecutive cursor-check mismatches
        self.cursor_ok = None
        self.active_gun = 0

        # ---- widgets
        self.mon_box = QComboBox()
        for m in self.monitors:
            self.mon_box.addItem(str(m), m.id)
        saved = self.store.data.get("monitor")
        if args.monitor is not None:
            self.mon_box.setCurrentIndex(min(args.monitor, len(self.monitors) - 1))
        elif saved:
            i = self.mon_box.findData(saved)
            if i >= 0:
                self.mon_box.setCurrentIndex(i)
        self.mon_box.currentIndexChanged.connect(self._monitor_changed)
        self.preset_box = QComboBox()
        for k, p in PRESETS.items():
            self.preset_box.addItem(p["label"], k)
        self.preset_box.setCurrentIndex(max(0, self.preset_box.findData(self.store.data.get("preset"))))
        self.preset_box.currentIndexChanged.connect(self._preset_changed)
        self.auto_preset = QCheckBox("Auto-pick from the emulator in front")
        self.auto_preset.setToolTip("Switches the preset when MAME, RetroArch, DuckStation, PCSX2, Flycast, "
                                    "Dolphin, Supermodel/Model 2 or TeknoParrot is the active window.")
        self.auto_preset.setChecked(self.store.data.get("auto_preset", True))
        self.auto_preset.toggled.connect(self._auto_preset_toggled)
        self._auto_exe = None
        self.live_binds = QCheckBox("Read the buttons from the emulator's own settings")
        self.live_binds.setToolTip("Looks at what MAME, RetroArch, DuckStation, PCSX2 or Dolphin is actually set to "
                                   "(including your own remaps) and makes the phone's buttons press exactly that.")
        self.live_binds.setChecked(self.store.data.get("live_binds", True))
        self.live_binds.toggled.connect(self._live_binds_toggled)
        self.auto_fix = QCheckBox("Set up the emulator automatically (backs up first)")
        self.auto_fix.setToolTip("When the emulator in front isn't set up for the gun, RetroSight fixes its "
                                 "settings right away. Every file is backed up first - 'Undo last fix' puts it back.")
        self.auto_fix.setChecked(self.store.data.get("auto_fix", True))
        self.auto_fix.toggled.connect(lambda on: (self.store.data.__setitem__("auto_fix", on), self.store.save()))
        self._auto_fixed: set = set()
        self._auto_fix_t = 0.0
        self._live = None            # bindread.Live for the emulator in front
        self._live_t = 0.0
        self.output_box = QComboBox()
        for o in ("mouse", "gamepad", "both"):
            self.output_box.addItem(o, o)
        self.output_box.setCurrentIndex(max(0, self.output_box.findData(self.store.data.get("output"))))
        self.output_box.currentIndexChanged.connect(self._output_changed)
        self.preset_notes = QLabel(wordWrap=True)
        self.w_spin = QDoubleSpinBox(decimals=3, minimum=0.0, maximum=10.0, singleStep=0.01, suffix=" m")
        self.h_spin = QDoubleSpinBox(decimals=3, minimum=0.0, maximum=10.0, singleStep=0.01, suffix=" m")
        self.diag_spin = QDoubleSpinBox(decimals=1, minimum=0.0, maximum=300.0, suffix=" in")
        save_size = QPushButton("Save size".upper())
        save_size.clicked.connect(self._save_size)
        from_diag = QPushButton("From diagonal".upper())
        from_diag.clicked.connect(self._size_from_diag)
        qr_btn = QPushButton("Show pairing QR".upper())
        qr_btn.clicked.connect(lambda: self.show_qr(1))
        self.p2_btn = QPushButton("Add player 2".upper())
        self.p2_btn.setToolTip("Shows a QR code that joins a second phone as player 2")
        self.p2_btn.clicked.connect(lambda: self.show_qr(2))
        self.pad_btn = QPushButton("Set up player 2 controller (driver)".upper())
        self.pad_btn.setToolTip("Player 2 aims through a virtual Xbox controller. Installs the free ViGEmBus "
                                "driver (Windows asks for permission).")
        self.pad_btn.clicked.connect(self._install_pad_driver)
        self.pad_btn.setVisible(False)
        self._joined: set[int] = set()     # player 2+ guns that said hello
        self._pad_tried = False
        self.hide_btn = QPushButton("Hide overlay".upper())
        self.hide_btn.clicked.connect(self.overlay.hide)
        self.test_btn = QPushButton("Accuracy test (30 shots)".upper())
        self.test_btn.clicked.connect(self._accuracy_test)
        self.status = QLabel()
        self.emu_box = QGroupBox("Is the emulator seeing the gun?")
        self.emu_label = QLabel("Start an emulator - its settings are checked here.", wordWrap=True)
        self.emu_label.setTextFormat(Qt.RichText)
        self.emu_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        emu_lay = QVBoxLayout(self.emu_box)
        emu_lay.addWidget(self.emu_label)
        self.fix_btn = QPushButton("Fix emulator settings for the gun".upper())
        self.fix_btn.setToolTip("Edits the emulator's own config files. Every file is backed up first "
                                "(Undo puts it back). Buttons you've bound yourself are kept. "
                                "RetroArch: sets the light gun for the last game you loaded.")
        self.fix_btn.clicked.connect(lambda: self._fix_emulator(confirm=True))
        self.fix_btn.setEnabled(False)
        self.choose_btn = QPushButton("Choose emulator…".upper())
        self.choose_btn.setToolTip("Point at mame.exe / retroarch.exe etc. if it isn't found automatically")
        self.choose_btn.clicked.connect(self._choose_emulator)
        fix_row = QHBoxLayout()
        self.undo_btn = QPushButton("Undo last fix".upper())
        self.undo_btn.setToolTip("Puts back the emulator's files from the newest backup")
        self.undo_btn.clicked.connect(self._undo_fix)
        self.undo_btn.setEnabled(False)
        fix_row.addWidget(self.fix_btn, 1)
        fix_row.addWidget(self.undo_btn)
        fix_row.addWidget(self.choose_btn)
        emu_lay.addLayout(fix_row)
        self.allow_phone_fix = QCheckBox("Let the phone change emulator settings without asking here")
        self.allow_phone_fix.setChecked(bool(self.store.data.get("allow_phone_fix", False)))
        self.allow_phone_fix.toggled.connect(lambda on: (self.store.data.__setitem__("allow_phone_fix", on),
                                                         self.store.save()))
        emu_lay.addWidget(self.allow_phone_fix)
        self._emu_scan_t = 0.0
        self._pending_fix = None     # (exe, [Edit]) waiting for the emulator to close
        self.status.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.log_view = QPlainTextEdit(readOnly=True)
        self.log_view.setMaximumBlockCount(500)
        self.logline.connect(self.log_view.appendPlainText)

        self.advanced_btn = QPushButton("Advanced mode".upper())
        self.advanced_btn.setCheckable(True)
        self.advanced_btn.setToolTip("Shows preset overrides, player 2 setup, the accuracy test and the raw log.")
        top_row = QHBoxLayout()
        top_row.addStretch(1)
        top_row.addWidget(self.advanced_btn)

        self.quick_steps = QLabel(
            "QUICK STEPS  1. Click SHOW PAIRING QR below.  2. On the phone, open RetroSight, tap SCAN QR, and "
            "follow its tutorial.  3. Open RetroArch/MAME and load a light-gun game.  4. If the PC says the "
            "emulator isn't seeing the gun, click FIX below (or let it fix itself).  5. Aim at the screen and fire.",
            wordWrap=True)
        self.quick_steps.setStyleSheet(f"color: {theme.CYAN}; font-weight: bold;")

        self.form = form = QFormLayout()
        form.addRow("Game monitor", self.mon_box)
        preset_row = QHBoxLayout()
        preset_row.addWidget(self.preset_box, 1)
        preset_row.addWidget(self.auto_preset)
        form.addRow("Emulator preset", preset_row)
        form.addRow("", self.live_binds)
        form.addRow("", self.auto_fix)
        form.addRow("Output", self.output_box)
        form.addRow("", self.preset_notes)
        size_row = QHBoxLayout()
        for wdg in (self.w_spin, QLabel("×"), self.h_spin, save_size):
            size_row.addWidget(wdg)
        form.addRow("Screen size", size_row)
        diag_row = QHBoxLayout()
        diag_row.addWidget(self.diag_spin)
        diag_row.addWidget(from_diag)
        form.addRow("…or diagonal", diag_row)
        btns = QHBoxLayout()
        for b in (qr_btn, self.p2_btn, self.pad_btn, self.hide_btn, self.test_btn):
            btns.addWidget(b)
        lay = QVBoxLayout(self)
        lay.addLayout(top_row)
        lay.addWidget(self.quick_steps)
        lay.addLayout(form)
        lay.addLayout(btns)
        lay.addWidget(self.status)
        lay.addWidget(self.emu_box)
        lay.addWidget(self.log_view)
        self.resize(640, 480)

        # Simple mode (default) shows only what's needed every session: monitor, pairing,
        # screen size, connection status, and "is the gun working / Fix it". Everything else
        # is one click away behind Advanced mode.
        self._advanced_rows = [preset_row, self.live_binds, self.auto_fix, self.output_box, self.preset_notes]
        self._advanced_widgets = [self.p2_btn, self.hide_btn, self.test_btn,
                                  self.undo_btn, self.choose_btn, self.allow_phone_fix, self.log_view]
        self.advanced_btn.setChecked(self.store.data.get("advanced_mode", False))
        self.advanced_btn.toggled.connect(self._set_advanced)
        self._set_advanced(self.advanced_btn.isChecked())

        h = QtLogHandler(self.logline.emit)
        h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s", "%H:%M:%S"))
        logging.getLogger().addHandler(h)

        # ---- runtime
        self._dry = args.dry_run
        self.gamepad = None
        # presets changed (v2: real emulator default buttons): refresh a saved preset once
        if self.store.data.get("preset_version", 1) < 2:
            pre = self.store.data.get("preset")
            if pre in PRESETS:
                out = self.store.data.get("output", "mouse")
                apply_preset(self.store.data, pre)
                self.store.data["output"] = out
            self.store.data["preset_version"] = 2
            self.store.save()
        self.injector = Injector(self.backend, self.monitor, self.store.data["buttons"],
                                 self.store.data["offscreen_trigger"], self.store.data["output"],
                                 self._ensure_gamepad(self.store.data["output"]))
        self._show_preset_notes()
        self._prev_trig = {}
        self._log_file = open(args.log, "ab") if args.log else None
        self.rx = Receiver(self.store.key, args.port or self.store.data["port"], self._on_aim,
                           lambda gid, msg, addr: self.bridge.ctrl.emit(gid, msg),
                           log_file=self._log_file).start()
        self.rx.probe_info = self._display_info  # called on the network thread; reads only
        self.injector.on_press = lambda gid, name, act: self.bridge.press.emit(gid, name, act)
        self._monitor_changed(self.mon_box.currentIndex())
        self._apply_players()
        self.auto_timer = QTimer(self, interval=1000, timeout=self._check_auto_preset)
        self.auto_timer.start()
        self.timer = QTimer(self, interval=500, timeout=self._refresh)
        # Wi-Fi dropped mid-press: release held buttons after 0.3 s of silence
        self.stale_timer = QTimer(self, interval=100, timeout=lambda: self.injector.release_stale(0.3))
        self.stale_timer.start()
        self._trim_since: dict[int, float] = {}
        self.timer.start()
        log.info("listening on UDP %s:%d", ", ".join(local_ips()), self.rx.port)
        if not self.store.data.get("paired_once"):
            QTimer.singleShot(300, self.show_qr)

    # ------------------------------------------------------------ properties
    @property
    def monitor(self):
        return self.monitors[max(0, self.mon_box.currentIndex())]

    def _qscreen(self):
        m = self.monitor
        screens = QGuiApplication.screens()
        for s in screens:
            if s.name() == m.name:
                return s
        for s in screens:  # fall back to geometry match
            g = s.geometry()
            if abs(g.x() - m.x) < 4 and abs(g.y() - m.y) < 4:
                return s
        return QGuiApplication.primaryScreen()

    # ------------------------------------------------------------ slots
    def _display_info(self) -> dict:
        m = self.monitor
        size = self.store.screen_size(m.id)
        return {"host": sys.platform, "host_name": socket.gethostname(),
                "display": {"id": m.id, "w_px": m.w, "h_px": m.h},
                "screen_size_m": list(size) if size else None}

    def _send_hello_ack(self, gid):
        self.rx.send_ctrl(gid, {"type": "hello_ack", "player": gid + 1, **self._display_info()})

    def _monitor_changed(self, _i):
        m = self.monitor
        self.store.data["monitor"] = m.id
        self.store.save()
        self.injector.monitor = m
        self.overlay.place_on(self._qscreen())
        self.calibs = {}            # one calibration state machine per gun (players calibrate separately)
        self.calib = self._calib(0)
        # tell connected phones right away: their screen anchor belongs to the old monitor
        if hasattr(self, "rx"):
            for gid, g in self.rx.guns.items():
                if g.addr is not None:
                    self._send_hello_ack(gid)
        size = self.store.screen_size(m.id)
        self.w_spin.setValue(size[0] if size else 0)
        self.h_spin.setValue(size[1] if size else 0)
        if size:
            self.diag_spin.setValue(math.hypot(*size) / 0.0254)

    def _calib(self, gid: int) -> Calibrator:
        c = self.calibs.get(gid)
        if c is None:
            m = self.monitor
            def _send(msg, g=gid):
                self.rx.send_ctrl(g, msg)
                self._on_calib_msg(msg)
            c = self.calibs[gid] = Calibrator(self.store, m.id, m.w, m.h,
                                              _send, self.overlay,
                                              lambda on, g=gid: self.injector.set_enabled(on, g))
        return c

    def _on_calib_msg(self, msg: dict):
        """Keep the Screen size fields in sync whenever a flow measures/re-measures the
        screen (A, D, Q) or just confirms the known one (B, C) - they only ever go stale
        otherwise, since calibration writes straight to the store, not through these spin boxes."""
        if msg.get("type") != "calib_result" or not msg.get("ok") or "screen" not in msg:
            return
        screen = msg["screen"]
        self.w_spin.setValue(screen["w_m"])
        self.h_spin.setValue(screen["h_m"])
        self.diag_spin.setValue(math.hypot(screen["w_m"], screen["h_m"]) / 0.0254)

    def _apply_players(self):
        """Player 2+: own buttons, and on Windows/macOS aim through a virtual gamepad."""
        preset = self.store.data.get("preset", "generic")
        live = self._live_for(preset)
        if live is not None:     # player 1 presses exactly what the emulator is set to
            self.injector.gun_buttons[0] = (dict(live.buttons), live.offscreen)
        else:
            self.injector.gun_buttons.pop(0, None)
        pad = self.gamepad
        if self._joined and pad is None and not sys.platform.startswith("linux") and not self._pad_tried:
            self._pad_tried = True
            pad = self._ensure_gamepad("gamepad")
        for gid in (1, 2, 3):
            out = "mouse" if sys.platform.startswith("linux") or pad is None else "gamepad"
            self.injector.gun_output[gid] = out
            self.injector.gun_buttons[gid] = player_buttons(preset, gid, out)
            if live is not None and live.p2 is not None:
                self.injector.gun_buttons[gid] = (dict(live.p2[0]), live.p2[1])
        if pad is not None:
            self.injector.gamepad = pad
        self.pad_btn.setVisible(self._advanced and bool(self._joined) and pad is None and sys.platform == "win32")

    def _install_pad_driver(self):
        import subprocess
        import threading
        self.overlay.show_message("Installing the player 2 controller driver - follow the Windows prompts", seconds=6)
        log.info("installing vgamepad (ViGEmBus driver) for player 2")

        def run():
            r = subprocess.run([sys.executable, "-m", "pip", "install", "vgamepad"], capture_output=True, text=True)
            log.info("pip install vgamepad: %s", "ok" if r.returncode == 0 else r.stderr[-400:])
            QTimer.singleShot(0, self._pad_installed)
        threading.Thread(target=run, daemon=True).start()

    def _pad_installed(self):
        self._pad_tried = False
        self.gamepad = None
        self._apply_players()
        ok = self.gamepad is not None
        self.overlay.show_message("Player 2 controller ready" if ok else
                                  "Driver not ready yet - restart the PC app (a reboot may be needed)", seconds=5)

    def _ensure_gamepad(self, output):
        if output == "mouse" or self.gamepad is not None:
            return self.gamepad
        self.gamepad = NullGamepad() if self._dry else make_gamepad()
        if self.gamepad is None:
            log.error("virtual gamepad unavailable: on Windows install the ViGEmBus driver and "
                      "`pip install vgamepad`; falling back to mouse output")
        return self.gamepad

    def _show_preset_notes(self):
        p = PRESETS.get(self.store.data.get("preset"), PRESETS["generic"])
        b = self.store.data["buttons"]
        live = self._live_for()
        if live is not None:
            bad = (f"\nNothing the phone can press: {', '.join(live.unbound)} - press 'Fix emulator settings'."
                   if live.unbound else "")
            self.preset_notes.setText(f"Buttons read from {live.emulator}'s settings "
                                      f"({' + '.join(live.sources)}):\n{live.summary()}{bad}")
            return
        self.preset_notes.setText(f"{p['notes']}\n"
                                  f"Trigger={b.get('trigger')}  Reload={b.get('reload')}  "
                                  f"A={b.get('a')}  B={b.get('b')}  Start={b.get('start')}  "
                                  f"Select={b.get('select')}  (edit config.json to customise)")

    def _preset_changed(self, _i):
        name = self.preset_box.currentData()
        keep_output = getattr(self, "_auto_switching", False)
        out = self.store.data.get("output", "mouse")
        p = apply_preset(self.store.data, name)
        if keep_output:  # auto switch: only the buttons follow the emulator, not mouse/gamepad
            self.store.data["output"] = out
        self.store.save()
        self.output_box.blockSignals(True)
        self.output_box.setCurrentIndex(self.output_box.findData(self.store.data["output"]))
        self.output_box.blockSignals(False)
        self._apply_output()
        log.info("preset: %s", p["label"])

    def _live_for(self, preset: str | None = None):
        """The emulator's own bindings if they're for the current preset (and the option is on)."""
        live = self._live
        if live is None or not self.live_binds.isChecked():
            return None
        return live if live.preset == (preset or self.store.data.get("preset")) else None

    def _live_binds_toggled(self, on: bool):
        self.store.data["live_binds"] = on
        self.store.save()
        self._live_t = 0.0
        self._apply_players()
        self._show_preset_notes()

    def _refresh_live(self, exe: str | None):
        """Re-read the emulator's button settings every couple of seconds while it's in front."""
        if not self.live_binds.isChecked() or not exe or preset_for(exe) is None:
            return
        now = time.monotonic()
        if now - self._live_t < 2.0:
            return
        self._live_t = now
        live = bindread.read(preset_for(exe), exe, foreground_title())
        if live is None:
            return
        if self._live is None or live.key() != self._live.key() or live.unbound != self._live.unbound:
            self._live = live
            log.info("%s buttons (%s): %s%s", live.emulator, " + ".join(live.sources), live.summary(),
                     f"; not pressable: {', '.join(live.unbound)}" if live.unbound else "")
            self._apply_players()
            self._show_preset_notes()

    def _labels(self, gid: int) -> dict:
        live = self._live_for()
        if live is not None:
            if gid == 0:
                return dict(live.labels)
            if live.p2 is not None:
                return dict(live.p2[2])
        return button_labels(self.store.data.get("preset", "generic"), gid, self.injector.out(gid))

    def _undo_fix(self):
        st = self._emu
        sets = emufix.backups(self._fix_emulator_name(st)) if st else []
        if not sets:
            QMessageBox.information(self, "Undo", "There's no backup to put back for this emulator.")
            return
        import json as _json
        m = _json.loads((sets[0] / "manifest.json").read_text(encoding="utf-8"))
        files = "\n".join("• " + f["path"] + ("  (new - will be removed)" if f["copy"] is None else "")
                          for f in m["files"])
        if emufix.is_running(st.exe):
            QMessageBox.warning(self, "Undo", f"Close {st.name} first - it saves its settings when it exits "
                                               f"and would overwrite the restored files.")
            return
        if QMessageBox.question(self, "Undo last fix", f"Put these back as they were on {m['time']}?\n\n{files}"
                                ) != QMessageBox.Yes:
            return
        try:
            done = emufix.restore(sets[0])
        except OSError as e:
            QMessageBox.warning(self, "Undo", f"Couldn't restore: {e}")
            return
        self._pending_fix = None
        self._emu_t = self._live_t = 0.0
        self.overlay.show_message(f"Restored {', '.join(done)}", seconds=4)
        log.info("undo: restored %s", ", ".join(done))

    @staticmethod
    def _fix_emulator_name(st) -> str:
        plan_names = {"retroarch": "RetroArch", "mame": "MAME", "duckstation": "DuckStation", "pcsx2": "PCSX2",
                      "model2_supermodel": "Supermodel", "dolphin": "Dolphin"}
        return plan_names.get(st.preset or "", st.name)

    def _is_gun_game(self, game: str) -> bool:
        if emufix.looks_like_gun_game(game):
            return True
        mame = next((p for p in self.store.data.get("emu_paths", []) if preset_for(p) == "mame"), None)
        return bool(emufix.mame_says_gun(game, mame))          # arcade short names (MAME / FBNeo cores)

    def _auto_setup(self):
        """No tinkering: when the emulator in front isn't set up for the gun, set it up (backed up first).
        Each different change is made once per run, so an emulator that undoes it can't cause a loop."""
        st = self._emu
        if (not self.auto_fix.isChecked() or st is None or self._pending_fix
                or emufix.PLANNERS.get(st.preset or "") is None
                or not (st.problems or len(self.rx.guns) > 1)):   # a 2nd phone may need player 2 set up
            return
        if st.preset == "retroarch" and not self._is_gun_game(st.game):
            return       # RetroArch sets the gun per game: leave normal games alone (the phone's FIX still works)
        now = time.monotonic()
        if now - self._auto_fix_t < 3.0:
            return
        self._auto_fix_t = now
        try:
            plan = emufix.plan_for(st.exe)
        except Exception as e:
            log.warning("auto setup: couldn't plan for %s: %s", st.name, e)
            return
        if plan is None or not plan.edits:
            return
        sig = (st.exe, tuple(plan.describe()))
        if sig in self._auto_fixed:
            return
        self._auto_fixed.add(sig)
        log.info("auto setup: %s isn't set up for the gun - fixing: %s", st.name, "; ".join(plan.describe()))
        res = self._fix_emulator(confirm=False)
        for gid, g in list(self.rx.guns.items()):
            if g.addr is not None:
                self.rx.send_ctrl(gid, {"type": "emu_fix_result", "ok": res["ok"],
                                        "message": f"Set up {st.name} for the gun automatically. {res['message']}"})

    def _auto_preset_toggled(self, on: bool):
        self.store.data["auto_preset"] = on
        self.store.save()
        self._auto_exe = None

    def _set_advanced(self, on: bool):
        self._advanced = on
        self.store.data["advanced_mode"] = on
        self.store.save()
        for row in self._advanced_rows:
            self.form.setRowVisible(row, on)
        for wdg in self._advanced_widgets:
            wdg.setVisible(on)
        if hasattr(self, "injector"):      # not yet built on the very first call, during __init__
            self._apply_players()

    # ------------------------------------------------------------ emulator check
    ACTION_WORDS = {"mouse:left": "left click", "mouse:right": "right click", "mouse:middle": "middle click"}

    def _action_text(self, act: str) -> str:
        if act in self.ACTION_WORDS:
            return self.ACTION_WORDS[act]
        kind, _, arg = act.partition(":")
        if kind == "key":
            return f"key {arg.upper() if len(arg) == 1 else arg}"
        if kind == "offscreen":
            return "off-screen shot (reload)"
        if kind == "pad":
            return f"pad {arg.upper()}"
        return "nothing (not mapped)"

    def _on_press(self, gid, name, act):
        label = {"select": "COIN/SELECT"}.get(name, name.upper())
        self._echo = (time.monotonic(), label, act)
        self.rx.send_ctrl(gid, {"type": "input_echo", "button": label, "sent": self._action_text(act)})
        self._show_emu()

    def _remember_emulator(self, exe: str):
        paths = [p for p in self.store.data.get("emu_paths", []) if p != exe]
        self.store.data["emu_paths"] = ([exe] + paths)[:10]
        self.store.save()

    def _choose_emulator(self):
        from PySide6.QtWidgets import QFileDialog
        flt = "Programs (*.exe)" if sys.platform == "win32" else "All files (*)"
        path, _ = QFileDialog.getOpenFileName(self, "Choose the emulator program", "", flt)
        if not path:
            return
        st = emucheck.check(path)
        if st is None:
            QMessageBox.warning(self, "Emulator", "That doesn't look like a supported emulator (MAME, RetroArch, "
                                "DuckStation, PCSX2, Flycast, Dolphin, Supermodel/Model 2, TeknoParrot).")
            return
        self._remember_emulator(os.path.normpath(path))
        self._emu, self._emu_t = st, time.monotonic()
        self._show_emu()

    def _update_emu(self, exe):
        now = time.monotonic()
        st = emucheck.check(exe) if exe else None
        if st is None and self._emu is None and now - self._emu_scan_t > 5:
            # nothing in front yet: a running emulator, else the last one used / chosen
            self._emu_scan_t = now
            for p in emulocate.running_emulators() + self.store.data.get("emu_paths", []):
                if os.path.exists(p):
                    st = emucheck.check(p)
                    if st:
                        break
        if st is not None:
            if exe and st.exe == exe and exe not in self.store.data.get("emu_paths", [])[:1]:
                self._remember_emulator(exe)
            self._emu, self._emu_t = st, now
        elif self._emu is not None and now - self._emu_t > 2.0:
            self._emu = emucheck.check(self._emu.exe) or self._emu  # re-read: settings may have been fixed
            self._emu_t = now
        self._show_emu()
        if self._emu is not None:
            for gid, g in list(self.rx.guns.items()):
                if g.addr is not None and time.monotonic() - g.last_rx < 3:
                    pad_missing = gid > 0 and self.injector.out(gid) == "mouse" and sys.platform == "win32"
                    msg = {**self._emu.to_msg(), "cursor_ok": self.cursor_ok if gid == 0 else None,
                           "labels": self._labels(gid)}
                    if pad_missing:
                        msg["checks"] = msg["checks"] + [{"ok": False, "text": "player 2 has no controller to aim with",
                                                          "fix": "On the PC app click 'Set up player 2 controller'"}]
                    self.rx.send_ctrl(gid, msg)

    def _fix_emulator(self, confirm: bool) -> dict:
        st = self._emu
        if st is None:
            return {"ok": False, "message": "No emulator seen yet - bring it to the front first."}
        plan = emufix.plan_for(st.exe)
        if plan is None:
            return {"ok": False, "message": f"Automatic fixing isn't available for {st.name} yet."}
        if not plan.edits:
            msg = " ".join(plan.notes) or "Nothing to change."
            if confirm:
                QMessageBox.information(self, "Emulator settings", msg)
            return {"ok": True, "message": msg}
        running = emufix.is_running(st.exe)
        if confirm:
            text = "These settings will be changed (a backup is kept):\n\n" + "\n".join(
                "• " + l for l in plan.describe())
            if running and any(e.when == "closed" for e in plan.edits):
                text += (f"\n\n{plan.emulator} is open: the items marked 'when it closes' are saved as soon "
                         f"as you close it.")
            if QMessageBox.question(self, "Fix emulator settings", text) != QMessageBox.Yes:
                return {"ok": False, "message": "cancelled"}
        done, waiting, errors = emufix.apply(plan, running)
        self._pending_fix = (st.exe, waiting) if waiting else None
        parts = []
        if errors:
            parts += errors
            if confirm:
                QMessageBox.warning(self, "Couldn't change the settings", "\n\n".join(errors))
        if done:
            parts.append("Saved: " + ", ".join(done) + ".")
        if waiting:
            parts.append(f"Close {plan.emulator} to finish ({len(waiting)} file(s) wait until it closes).")
        if plan.emulator == "RetroArch":
            parts.append("Then load the game again.")
        elif plan.emulator == "MAME":
            parts.append("Restart MAME.")
        msg = " ".join(parts)
        if errors and not done and not waiting:
            log.warning("emulator fix failed: %s", msg)
            self.overlay.show_message(msg, seconds=6)
            return {"ok": False, "message": msg}
        log.info("emulator fix: %s", msg)
        self.overlay.show_message(msg, seconds=5)
        self._emu_t = 0.0  # re-check right away
        return {"ok": True, "message": msg}

    def _check_pending_fix(self):
        if not self._pending_fix:
            return
        exe, edits = self._pending_fix
        if emufix.is_running(exe):
            return
        failed = []
        backup = emufix.Backup(self._fix_emulator_name(self._emu) if self._emu else os.path.basename(exe))
        for e in edits:
            try:
                emufix.apply_edit(e, backup)
            except PermissionError:
                failed.append(emufix.permission_hint(e.path))
            except OSError as ex:
                failed.append(f"Couldn't save {e.path.name}: {ex}")
        self._pending_fix = None
        if failed:
            log.error("emulator fix: %s", " ".join(failed))
            self.overlay.show_message(" ".join(failed), seconds=8)
            return
        log.info("emulator fix: finished after %s closed", os.path.basename(exe))
        self.overlay.show_message(f"Gun settings saved - start {os.path.basename(exe)} again", seconds=5)

    def _show_emu(self):
        rows = []
        st = self._emu
        self.fix_btn.setEnabled(st is not None and emufix.PLANNERS.get(st.preset or "") is not None)
        self.undo_btn.setEnabled(st is not None and bool(emufix.backups(self._fix_emulator_name(st))))
        if self._pending_fix:
            rows.append("<b>Waiting for the emulator to close to save the rest of the fix…</b>")
        if st is None:
            rows.append("Start an emulator (MAME, RetroArch, ...) - its gun settings are checked here.")
        else:
            bad = st.problems
            head = f"<b>{st.name}</b>" + (f" · {st.game}" if st.game else "")
            rows.append(head + (f" — <span style='color:{theme.LIME}'><b>set up for the gun ✓</b></span>" if not bad
                                else f" — <span style='color:{theme.RED}'><b>{len(bad)} problem(s)</b></span>"))
            for c in st.checks:
                mark = f"<span style='color:{theme.LIME}'>✓</span>" if c.ok else f"<span style='color:{theme.RED}'>✗</span>"
                rows.append(f"{mark} {c.text}" + ("" if c.ok or not c.fix else f"<br>&nbsp;&nbsp;&nbsp;→ {c.fix}"))
        if self.cursor_ok is True:
            rows.append(f"<span style='color:{theme.LIME}'>✓</span> Windows cursor follows the gun")
        elif self.cursor_ok is False:
            rows.append(f"<span style='color:{theme.RED}'>✗</span> Windows cursor is NOT following the gun → if the "
                        "emulator runs as administrator, run start-host.bat as administrator too")
        if self._echo and time.monotonic() - self._echo[0] < 30:
            _, label, act = self._echo
            rows.append(f"Last button: <b>{label}</b> → sent <b>{self._action_text(act)}</b> "
                        f"(if the game didn't react, that key/button isn't what the emulator expects)")
        else:
            rows.append("Press a button on the phone to see what is sent.")
        self.emu_label.setText("<br>".join(rows))

    def _check_cursor(self):
        lm = self.injector.last_move
        pos = self.backend.cursor_pos()
        if lm is None or pos is None or time.monotonic() - lm[0] > 0.3:
            return  # gun not moving the cursor right now: nothing to compare
        if self.injector._offscreen_hold:
            return
        off = max(abs(pos[0] - lm[1]), abs(pos[1] - lm[2]))
        self._cursor_bad = self._cursor_bad + 1 if off > 8 else 0
        self.cursor_ok = self._cursor_bad < 3

    def _warn_backend(self):
        if self.backend_problem:
            from PySide6.QtWidgets import QMessageBox
            QMessageBox.warning(self, "RetroSight", self.backend_problem)

    def _watchdogs(self):
        for c in self.calibs.values():
            c.tick()
        # the phone's "trim off" got lost: don't leave that gun paused behind a target
        for gid, t0 in list(self._trim_since.items()):
            if time.monotonic() - t0 > 15:
                log.info("gun %d: trim timed out, resuming", gid)
                self._trim_since.pop(gid, None)
                self.injector.set_enabled(True, gid)
                if self.overlay.mode == "targets":
                    self.overlay.hide()

    def _check_auto_preset(self):
        self._watchdogs()
        exe = foreground_exe()
        self._check_pending_fix()
        self._check_cursor()
        self._update_emu(exe)
        self._refresh_live(exe)
        self._auto_setup()
        if not self.auto_preset.isChecked():
            return
        if exe == self._auto_exe:
            return
        self._auto_exe = exe
        name = preset_for(exe)  # None for anything that isn't a known emulator: keep the current preset
        if name is None or name == self.store.data.get("preset"):
            return
        i = self.preset_box.findData(name)
        if i < 0:
            return
        log.info("auto preset: %s is in front", exe.replace("\\", "/").rsplit("/", 1)[-1] if exe else "?")
        self._auto_switching = True
        try:
            self.preset_box.setCurrentIndex(i)
        finally:
            self._auto_switching = False

    def _output_changed(self, _i):
        self.store.data["output"] = self.output_box.currentData()
        self.store.save()
        self._apply_output()

    def _apply_output(self):
        out = self.store.data["output"]
        pad = self._ensure_gamepad(out)
        if out != "mouse" and pad is None:
            out = "mouse"
        self.injector.configure(self.store.data["buttons"], self.store.data["offscreen_trigger"], out, pad)
        self._apply_players()
        self._show_preset_notes()

    def _save_size(self):
        w, h = self.w_spin.value(), self.h_spin.value()
        if w > 0.05 and h > 0.05:
            self.store.set_screen_size(self.monitor.id, w, h, "manual")
            log.info("saved screen size %.3f x %.3f m for %s", w, h, self.monitor.id)

    def _size_from_diag(self):
        d = self.diag_spin.value() * 0.0254
        m = self.monitor
        a = math.atan2(m.h, m.w)
        self.w_spin.setValue(d * math.cos(a))
        self.h_spin.setValue(d * math.sin(a))
        self._save_size()

    def show_qr(self, player: int = 1):
        ip = local_ips()[0]
        uri = P.pairing_uri(ip, self.rx.port, self.store.key, player - 1)
        who = "" if player == 1 else f"PLAYER {player}: "
        self.overlay.show_qr(uri, f"{who}Scan with the RetroSight app  —  {ip}:{self.rx.port}   (Esc to close)")

    def _accuracy_test(self):
        self.calib.start({"type": "calib_request", "flow": "T", "ar_session": self.calib.ar_session})

    def _on_ctrl(self, gid, msg):
        t = msg.get("type")
        if t == "hello":
            self.active_gun = gid
            self.store.data["paired_once"] = True
            self.store.save()
            if self.overlay.mode == "qr":
                self.overlay.show_message(f"Paired with {msg.get('device', 'phone')}", seconds=2)
            self._send_hello_ack(gid)
            log.info("hello from gun %d (%s)", gid, msg.get("device"))
            if gid > 0 and gid not in self._joined:
                self._joined.add(gid)
                self._apply_players()
                if self.injector.out(gid) == "mouse" and not sys.platform.startswith("linux"):
                    self.overlay.show_message(f"PLAYER {gid + 1} JOINED - click 'Set up player 2 controller' "
                                              f"in the PC app so both guns can aim", seconds=6)
                else:
                    self.overlay.show_message(f"PLAYER {gid + 1} JOINED", seconds=3)
        elif t == "ping":
            self.last_ping = msg.get("stats", {})
        elif t == "trim":      # after a quick lock-on: one shot at the centre target fine-tunes the aim
            if msg.get("on"):
                self._trim_since[gid] = time.monotonic()
                self.injector.set_enabled(False, gid)     # that shot must not reach the game
                who = f"PLAYER {gid + 1}: " if gid > 0 else ""
                self.overlay.show_targets([(0.5, 0.5)], 0, f"{who}Shoot the center target to fine-tune the aim")
            else:
                self._trim_since.pop(gid, None)
                self.injector.set_enabled(True, gid)
                if self.overlay.mode == "targets":
                    self.overlay.hide()                  # hands the focus back to the game
        elif t == "emu_fix":   # "FIX IT" tapped on the phone
            allowed = self.allow_phone_fix.isChecked()
            if not allowed:  # the person at the PC decides
                self.rx.send_ctrl(gid, {"type": "emu_fix_result", "ok": False,
                                        "message": "Confirm on the PC screen..."})
                self.raise_(); self.activateWindow()
            res = self._fix_emulator(confirm=not allowed)
            self.rx.send_ctrl(gid, {"type": "emu_fix_result", **res})
        else:
            if gid == 0:
                self.active_gun = gid
            self._calib(gid).handle(msg)

    def _on_aim(self, pkt: P.AimPacket):
        # receiver thread
        self.injector.update(pkt)
        prev = self._prev_trig.get(pkt.gun_id, False)
        now = bool(pkt.buttons & P.BTN_TRIGGER)
        self._prev_trig[pkt.gun_id] = now
        if now and not prev and pkt.gun_id == 0 and self.calib.s is not None and self.calib.s.flow == "T":
            self.bridge.trigger.emit(pkt.gun_id, pkt.x, pkt.y, pkt.on_screen)

    def _on_trigger(self, gid, x, y, on):
        self.calib.on_trigger_edge(x, y, on)

    def _refresh(self):
        lines = []
        if self.backend_problem:
            lines.append("PROBLEM: " + self.backend_problem)
        now = time.monotonic()
        for gid, g in sorted(self.rx.guns.items()):
            pkt = self.injector.last.get(gid)
            age = now - g.last_rx if g.last_rx else float("inf")
            state = "connected" if age < 1.5 else "idle"
            if pkt:
                fl = []
                if not pkt.calibrated:
                    fl.append("uncalibrated")
                if not pkt.flags & P.FLAG_TRACKING:
                    fl.append("NO TRACKING")
                if g.net["loss"] > 3 or g.net["late_ms"] > 45:
                    fl.append(f"WI-FI WEAK ({g.net['loss']:.0f}% lost, {g.net['late_ms']:.0f} ms late)")
                if pkt.flags & P.FLAG_GYRO_ONLY:
                    fl.append("gyro-only")
                lines.append(f"Gun {gid + 1}: {state}, {g.rate_hz:.0f} Hz, x={pkt.x:.3f} y={pkt.y:.3f} "
                             f"{'on' if pkt.on_screen else 'OFF'}-screen {' '.join(fl)}")
            else:
                lines.append(f"Gun {gid + 1}: {state} (control only)")
        stats = getattr(self, "last_ping", {}) or {}
        if stats:
            lines.append(f"phone: battery {stats.get('battery')}%, reloads {stats.get('reloads', 0)}, "
                         f"anchor map corrections {stats.get('anchor_jumps', 0)}")
        lines.append(f"output {self.injector.output}, monitor {self.injector.monitor.w}x{self.injector.monitor.h}; "
                     f"bad MAC {self.rx.bad_mac}, dropped {self.rx.dropped}")
        self.status.setText("\n".join(lines) if lines else "waiting for phone…")

    def closeEvent(self, ev):
        self.injector.set_enabled(False)
        self.rx.stop()
        self.backend.close()
        if self.gamepad:
            self.gamepad.close()
        if self._log_file:
            self._log_file.close()
        self.overlay.close()
        super().closeEvent(ev)


def main(argv=None):
    ap = argparse.ArgumentParser(description="RetroSight PC host")
    ap.add_argument("--port", type=int, default=None, help="UDP port (default from config, 47800)")
    ap.add_argument("--monitor", type=int, default=None, help="monitor index to drive")
    ap.add_argument("--new-key", action="store_true", help="regenerate the pairing key")
    ap.add_argument("--log", help="append raw packets to this binary log (tools/log_replay.py)")
    ap.add_argument("--dry-run", action="store_true", help="do not inject input (testing)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    from logging.handlers import RotatingFileHandler
    from .store import config_dir
    # basicConfig first: it does nothing if the root logger already has a handler
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config_dir().mkdir(parents=True, exist_ok=True)
    fh = RotatingFileHandler(config_dir() / "host.log", maxBytes=2_000_000, backupCount=2, encoding="utf-8")
    fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    logging.getLogger().addHandler(fh)
    if sys.platform == "win32":
        from .inject.windows import set_dpi_aware
        set_dpi_aware()
    QGuiApplication.setHighDpiScaleFactorRoundingPolicy(Qt.HighDpiScaleFactorRoundingPolicy.PassThrough)
    app = QApplication(sys.argv[:1])
    theme.apply_theme(app)
    icon = theme.make_crosshair_icon()
    app.setWindowIcon(icon)
    w = HostWindow(args)
    w.setWindowIcon(icon)
    w.show()
    QTimer.singleShot(800, w._warn_backend)   # e.g. Linux without /dev/uinput access
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
