"""Host-side calibration state machine (flows A-D + accuracy test).

UI-agnostic: drives an object implementing `CalibUI` and sends control messages
through `send(msg)`. All methods must be called on one thread (the Qt thread).
"""
from __future__ import annotations

import csv
import logging
import math
import random
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Protocol

import numpy as np

from . import solver
from .geometry import Barrel, Screen

log = logging.getLogger(__name__)

INSET = 0.15
TARGETS_C = [(INSET, INSET), (1 - INSET, INSET), (1 - INSET, 1 - INSET), (INSET, 1 - INSET)]
TARGETS_B = [(0.5, 0.5)] + TARGETS_C
GRID9 = [(x, y) for y in (INSET, 0.5, 1 - INSET) for x in (INSET, 0.5, 1 - INSET)]
TARGETS_D = GRID9 + GRID9
RETRY_RMS_FRACTION = 0.01   # flow C: ask for a 5th shot if RMS > 1% of width
MAX_RMS_FRACTION = 0.04     # any flow: reject the result above 4% of width (~77 px at 1080p)
ARUCO_MIN_VIEWS = 20
ARUCO_MIN_BASELINE = 0.20   # metres of camera-centre spread
ARUCO_MAX_VIEWS = 80
QUICK_VIEWS = 4             # flow Q: views fitted jointly
QUICK_MAX_VIEWS = 40


class CalibUI(Protocol):
    def show_targets(self, targets, current: int, message: str) -> None: ...
    def show_markers(self, markers: list, message: str, progress: float) -> None: ...
    def show_message(self, message: str, seconds: float = 0) -> None: ...
    def hide(self) -> None: ...


@dataclass
class Marker:
    id: int
    x: int      # top-left, display pixels
    y: int
    size: int

    def corners(self):
        s = self.size
        return [(self.x, self.y), (self.x + s, self.y), (self.x + s, self.y + s), (self.x, self.y + s)]


def marker_layout(w_px: int, h_px: int) -> list[Marker]:
    m = int(round(0.18 * h_px))
    g = int(round(0.05 * h_px))
    return [Marker(0, g, g, m), Marker(1, w_px - g - m, g, m),
            Marker(2, w_px - g - m, h_px - g - m, m), Marker(3, g, h_px - g - m, m),
            Marker(4, (w_px - m) // 2, (h_px - m) // 2, m)]


@dataclass
class Session:
    flow: str
    targets: list
    barrel: Barrel
    fit_t: bool = False
    index: int = 0
    shots: list = field(default_factory=list)
    views: list = field(default_factory=list)
    retried: bool = False
    test_rows: list = field(default_factory=list)


class Calibrator:
    def __init__(self, store, display_id: str, w_px: int, h_px: int,
                 send: Callable[[dict], None], ui: CalibUI, set_injection: Callable[[bool], None]):
        self.store = store
        self.display_id = display_id
        self.w_px, self.h_px = w_px, h_px
        self.send = send
        self.ui = ui
        self.set_injection = set_injection
        self.s: Session | None = None
        self.screen: Screen | None = None     # last solved screen in the phone's current AR world
        self.ar_session: str | None = None
        self.markers = marker_layout(w_px, h_px)
        self.last_rx = 0.0                    # when the phone last sent us anything for calibration
        self._last_result = None              # (time, msg): re-sent if the phone missed it

    # ------------------------------------------------------------ helpers
    @property
    def size_m(self):
        return self.store.screen_size(self.display_id)

    def _target_msg(self, retry=False):
        s = self.s
        x, y = s.targets[s.index]
        self.send({"type": "calib_target", "flow": s.flow, "index": s.index, "total": len(s.targets),
                   "x": x, "y": y, "retry": retry})
        hint = ""
        if s.flow == "D" and s.index >= 9:
            hint = "  (second position: step at least 1 m to the side)"
        self.ui.show_targets(s.targets, s.index, f"Flow {s.flow}: aim at the target and fire "
                             f"({s.index + 1}/{len(s.targets)}){hint}")

    def _finish(self, msg: dict, ui_text: str, seconds: float = 4):
        self.send(msg)
        self._last_result = (time.monotonic(), msg)
        self.ui.show_message(ui_text, seconds=seconds)
        self.s = None
        self.set_injection(True)

    def _log_attempt(self, flow, r):
        log.info("flow %s attempt: ok=%s rms_px=%.2f p80_px=%s rms_mm=%s width=%s msg=%s", flow, r.ok, r.rms_px,
                 getattr(r, "reproj_p80", None), None if r.rms_mm is None else round(r.rms_mm, 2),
                 None if r.screen is None else round(r.screen.width_m, 4), r.message)

    def _error(self, flow, text):
        log.warning("flow %s failed: %s", flow, text)
        self._finish({"type": "calib_error", "flow": flow, "message": text}, f"Calibration failed: {text}")

    # ------------------------------------------------------------ dispatch
    def tick(self):
        """Called every second: a phone that vanished mid-calibration must not leave the
        game paused and the markers on screen forever."""
        if self.s is not None and time.monotonic() - self.last_rx > 25:
            log.warning("flow %s: phone went quiet, cancelling", self.s.flow)
            self._finish({"type": "calib_error", "flow": self.s.flow, "message": "phone stopped answering"},
                         "Calibration cancelled - the phone stopped answering", seconds=3)

    def handle(self, msg: dict):
        t = msg.get("type")
        self.last_rx = time.monotonic()
        # UDP lost our result: the phone is still sending shots/views for a flow we already finished
        if t in ("shot", "aruco_obs") and self.s is None and self._last_result \
                and time.monotonic() - self._last_result[0] < 20:
            self.send(self._last_result[1])
            return
        if t in ("calib_request", "calib_cancel", "shot"):
            log.info("phone -> %s %s", t, {k: v for k, v in msg.items() if k in ("flow", "index", "n", "w_rms")})
        if t == "calib_request":
            self.start(msg)
        elif t == "calib_cancel" and self.s:
            self._finish({"type": "calib_error", "flow": self.s.flow, "message": "cancelled"}, "Cancelled")
        elif t == "shot" and self.s and self.s.flow in "BCD":
            self.on_shot(msg)
        elif t == "aruco_obs" and self.s and self.s.flow == "A":
            self.on_aruco(msg)
        elif t == "aruco_obs" and self.s and self.s.flow == "Q":
            self.on_quick(msg)

    def start(self, msg: dict):
        flow = str(msg.get("flow", "C")).upper()
        sess = msg.get("ar_session")
        if sess != self.ar_session:
            self.screen = None  # different AR world: old screen pose is meaningless
            self.ar_session = sess
        barrel = Barrel.from_dict(msg["barrel"]) if msg.get("barrel") else Barrel()
        fit_t = bool(msg.get("fit_t", False))
        if flow in "BCQ" and self.size_m is None:
            return self._error(flow, "screen size unknown: run flow A, or enter the size on the host")
        if flow == "B" and self.screen is None:
            return self._error("B", "scan the TV first (stage A) in this session, then run B")
        self.set_injection(False)
        if flow == "Q":
            self.s = Session("Q", [], barrel)
            self.send({"type": "calib_progress", "flow": "Q", "fraction": 0.0, "views": 0,
                       "message": "Point the BACK CAMERA at the screen"})
            self.ui.show_markers(self.markers, "Quick lock: point the phone's BACK CAMERA at the screen (phone sideways, its screen facing you)", 0.0)
            return
        if flow == "A":
            self.s = Session("A", [], barrel)
            self.send({"type": "calib_progress", "flow": "A", "fraction": 0.0, "views": 0,
                       "message": "Point the camera at the screen and sweep slowly"})
            self.ui.show_markers(self.markers, "Flow A: sweep the phone camera slowly across the screen", 0.0)
            return
        targets = {"B": TARGETS_B, "C": TARGETS_C, "D": TARGETS_D, "T": None}[flow]
        if flow == "T":
            rnd = random.Random()
            targets = [(rnd.uniform(0.1, 0.9), rnd.uniform(0.1, 0.9)) for _ in range(30)]
        self.s = Session(flow, list(targets), barrel, fit_t)
        self._target_msg()

    # ------------------------------------------------------------ B / C / D
    def on_shot(self, msg: dict):
        s = self.s
        if int(msg.get("index", -1)) != s.index:
            return self._target_msg(retry=True)
        s.shots.append(solver.Shot.from_msg(s.targets[s.index], msg))
        s.index += 1
        if s.index < len(s.targets):
            return self._target_msg()
        self._solve()

    def _solve(self):
        s = self.s
        w_px, h_px = self.w_px, self.h_px
        if s.flow == "C":
            w_m, h_m = self.size_m
            r = solver.solve_screen(s.shots, s.barrel, w_m, h_m, w_px, h_px)
            self._log_attempt("C", r)
            if r.ok and r.rms_px > RETRY_RMS_FRACTION * w_px and not s.retried:
                s.retried = True
                s.targets.append((0.5, 0.5))
                return self._target_msg()
            if not r.ok:
                return self._error("C", r.message)
            if r.rms_px > MAX_RMS_FRACTION * w_px:
                return self._error("C", f"shots disagree by {r.rms_px:.0f} px - aim carefully and hold the phone "
                                        f"the way you calibrated the barrel (B)")
            self.screen = r.screen
            self._finish({"type": "calib_result", "flow": "C", "ok": True, "rms_px": r.rms_px,
                          "screen": r.screen.to_dict(), "message": f"RMS {r.rms_px:.1f} px"},
                         f"Re-anchored. RMS {r.rms_px:.1f} px")
        elif s.flow == "B":
            r = solver.solve_barrel(s.shots, self.screen, w_px, h_px, t_init=s.barrel.t, fit_t=s.fit_t)
            self._log_attempt("B", r)
            if r.rms_px > MAX_RMS_FRACTION * w_px:
                return self._error("B", f"shots disagree by {r.rms_px:.0f} px - hold the phone the same way for "
                                        f"every shot, aim carefully, and re-scan the TV if you moved it")
            self._finish({"type": "calib_result", "flow": "B", "ok": True, "rms_px": r.rms_px,
                          "barrel": r.barrel.to_dict(), "screen": self.screen.to_dict(),
                          "message": f"RMS {r.rms_px:.1f} px"},
                         f"Barrel calibrated. RMS {r.rms_px:.1f} px")
        elif s.flow == "D":
            init_w = self.size_m[0] if self.size_m else 1.2
            r = solver.solve_joint(s.shots, w_px, h_px, s.barrel, fit_t=s.fit_t, width_init=init_w)
            self._log_attempt("D", r)
            if r.ok and r.rms_px > MAX_RMS_FRACTION * w_px:
                r.ok = False
                r.message = f"shots disagree by {r.rms_px:.0f} px - aim more carefully"
            if r.ok:
                self.screen = r.screen
                self.store.set_screen_size(self.display_id, r.screen.width_m, r.screen.height_m, "flow D")
            self._finish({"type": "calib_result", "flow": "D", "ok": r.ok, "rms_px": r.rms_px,
                          "screen": r.screen.to_dict(), "barrel": r.barrel.to_dict(),
                          "message": r.message or f"RMS {r.rms_px:.1f} px"},
                         f"Joint solve: width {r.screen.width_m * 100:.1f} cm, RMS {r.rms_px:.1f} px "
                         f"{r.message}")

    # ------------------------------------------------------------ A
    def on_aruco(self, msg: dict):
        s = self.s
        if len(msg.get("markers", {})) < 2:
            return
        try:
            s.views.append(solver.CameraView.from_msg(msg))
        except (KeyError, ValueError, TypeError):
            return
        n = len(s.views)
        base = solver.view_baseline(s.views)
        frac = min(1.0, n / ARUCO_MIN_VIEWS) * min(1.0, base / ARUCO_MIN_BASELINE)
        if n % 3 == 0 or frac >= 1:
            self.send({"type": "calib_progress", "flow": "A", "fraction": frac, "views": n,
                       "message": "keep sweeping" if frac < 1 else "solving"})
            self.ui.show_markers(self.markers, f"Flow A: {n} views, baseline {base * 100:.0f} cm", frac)
        if frac < 1 or (n % 5 and n < ARUCO_MAX_VIEWS):
            return
        corner_px = {(m.id, k): c for m in self.markers for k, c in enumerate(m.corners())}
        r = solver.solve_screen_aruco(s.views, corner_px, self.w_px, self.h_px, ARUCO_MIN_VIEWS)
        self._log_attempt("A", r)
        if not r.ok and n < ARUCO_MAX_VIEWS:
            return  # keep collecting; more views improve triangulation
        if not r.ok:
            return self._error("A", r.message)
        self.screen = r.screen
        self.store.set_screen_size(self.display_id, r.screen.width_m, r.screen.height_m, "flow A")
        self._finish({"type": "calib_result", "flow": "A", "ok": True, "rms_px": r.rms_px,
                      "rms_mm": r.rms_mm, "screen": r.screen.to_dict(),
                      "message": f"{r.screen.width_m * 100:.1f} x {r.screen.height_m * 100:.1f} cm, "
                                 f"residual {r.rms_mm:.1f} mm"},
                     f"Screen measured: {r.screen.width_m * 100:.1f} x {r.screen.height_m * 100:.1f} cm "
                     f"(diag {math.hypot(r.screen.width_m, r.screen.height_m) / 0.0254:.1f} in), "
                     f"residual {r.rms_mm:.1f} mm")

    # ------------------------------------------------------------ Q (quick lock)
    def on_quick(self, msg: dict):
        """Known screen size: a few views of the markers re-place the screen in the phone's
        current AR world. Used after the phone restarts / sleeps / loses its room map."""
        s = self.s
        try:
            v = solver.CameraView.from_msg(msg)
        except (KeyError, ValueError, TypeError):
            return
        if len(v.corners) < 8:
            return
        s.views.append(v)
        n = len(s.views)
        self.send({"type": "calib_progress", "flow": "Q", "fraction": min(1.0, n / QUICK_VIEWS), "views": n,
                   "message": "hold steady"})
        if n < QUICK_VIEWS:
            return
        w_m, h_m = self.size_m
        corner_px = {(m.id, k): c for m in self.markers for k, c in enumerate(m.corners())}
        r = solver.solve_screen_aruco_known(s.views[-QUICK_VIEWS * 2:], corner_px, self.w_px, self.h_px, w_m, h_m)
        self._log_attempt("Q", r)
        if not r.ok:
            if n >= QUICK_MAX_VIEWS:
                return self._error("Q", r.message)
            return
        self.screen = r.screen
        self._finish({"type": "calib_result", "flow": "Q", "ok": True, "rms_px": r.rms_px,
                      "screen": r.screen.to_dict(), "message": "Locked on"}, "Locked on", seconds=1)

    # ------------------------------------------------------------ accuracy test (tools harness)
    def on_trigger_edge(self, x: float, y: float, on_screen: bool):
        """Called (on the UI thread) for each trigger press while flow T runs."""
        s = self.s
        if not s or s.flow != "T":
            return
        tx, ty = s.targets[s.index]
        s.test_rows.append((s.index, tx, ty, x, y, int(on_screen), time.time()))
        s.index += 1
        if s.index < len(s.targets):
            return self._target_msg()
        rows = np.array([[r[1], r[2], r[3], r[4]] for r in s.test_rows])
        ex = rows[:, 2] - rows[:, 0]
        ey = (rows[:, 3] - rows[:, 1]) * self.h_px / self.w_px  # in screen-width units
        rms = float(np.sqrt(np.mean(ex ** 2 + ey ** 2)))
        out = Path.cwd() / f"accuracy_{time.strftime('%Y%m%d_%H%M%S')}.csv"
        with out.open("w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["index", "target_x", "target_y", "hit_x", "hit_y", "on_screen", "t"])
            w.writerows(s.test_rows)
        log.info("accuracy: RMS %.3f%% of width over %d shots -> %s", rms * 100, len(rows), out)
        self._finish({"type": "calib_result", "flow": "T", "ok": True, "rms_px": rms * self.w_px,
                      "message": f"RMS {rms * 100:.2f}% of width"},
                     f"Accuracy: RMS {rms * 100:.2f}% of screen width ({rms * self.w_px:.1f} px). "
                     f"Target <= 0.5%. Saved {out.name}")
