"""Input injection front-end: maps aim packets to absolute mouse / virtual gamepad + buttons/keys."""
from __future__ import annotations

import logging
import sys
import threading
import time
from dataclasses import dataclass

from .. import protocol as P

log = logging.getLogger(__name__)


@dataclass
class Monitor:
    id: str        # stable-ish id: device name + resolution
    name: str
    x: int         # virtual-desktop pixel rect
    y: int
    w: int
    h: int
    primary: bool = False

    def __str__(self):
        return f"{self.name} {self.w}x{self.h} @({self.x},{self.y}){' primary' if self.primary else ''}"


class Backend:
    """Platform backend. Actions are strings: 'mouse:left|right|middle', 'key:<name>'."""

    def monitors(self) -> list[Monitor]:
        raise NotImplementedError

    def move_abs(self, gun_id: int, mon: Monitor, x: float, y: float):
        raise NotImplementedError

    def action(self, gun_id: int, action: str, down: bool):
        raise NotImplementedError

    def cursor_pos(self):
        """Where the OS cursor really is (virtual-desktop pixels), or None if unknown."""
        return None

    def close(self):
        pass


class NullBackend(Backend):
    def __init__(self):
        self.events = []
        self.mons = [Monitor("null", "null", 0, 0, 1920, 1080, True)]

    def monitors(self):
        return self.mons

    def move_abs(self, gun_id, mon, x, y):
        self.events.append(("move", gun_id, x, y))

    def action(self, gun_id, action, down):
        self.events.append(("action", gun_id, action, down))


BACKEND_PROBLEM: str | None = None   # plain-language reason the real backend couldn't start


def make_backend() -> Backend:
    global BACKEND_PROBLEM
    try:
        if sys.platform == "win32":
            from .windows import WindowsBackend
            return WindowsBackend()
        if sys.platform.startswith("linux"):
            from .linux import LinuxBackend
            return LinuxBackend()
        if sys.platform == "darwin":
            from .macos import MacBackend
            return MacBackend()
    except Exception as e:  # missing optional deps etc.
        log.error("input backend unavailable (%s); using null backend", e)
        BACKEND_PROBLEM = str(e) if isinstance(e, PermissionError) else (
            f"The gun can't move the cursor on this PC ({e}). Re-run the start script to reinstall.")
    return NullBackend()


MONITOR_REFRESH_S = 2.0


class Injector:
    """Thread-safe. `output` is 'mouse', 'gamepad' or 'both'."""

    def __init__(self, backend: Backend, monitor: Monitor, buttons: dict, offscreen_trigger: str,
                 output: str = "mouse", gamepad=None):
        self.backend = backend
        self.monitor = monitor
        self.buttons = buttons
        self.offscreen_trigger = offscreen_trigger
        self.output = output
        self.gamepad = gamepad
        self.enabled = True
        self._prev: dict[int, int] = {}
        self._held: dict[tuple[int, int], str] = {}   # (gun, bit) -> action pressed
        self._offscreen_hold: dict[int, int] = {}     # gun -> number of offscreen actions held
        self.last: dict[int, P.AimPacket] = {}
        self._lock = threading.Lock()
        self._mon_checked = time.monotonic()
        self.on_press = None          # callback(gun_id, button_name, action) for the button echo
        self.gun_output: dict[int, str] = {}       # per-gun override of `output` (player 2+)
        self.gun_buttons: dict[int, tuple] = {}    # per-gun (buttons, offscreen_trigger)
        self.paused_guns: set[int] = set()         # guns whose input is held during their calibration
        self.user_paused: set[int] = set()         # guns the player released so the real mouse is free
        self.last_move = None         # (t, x_px, y_px) of the last cursor move we sent
        self._last_t: dict[int, float] = {}        # gun -> time of its last packet
        self._presses: dict[int, int] = {}         # gun -> press counters we have acted on

    def configure(self, buttons: dict, offscreen_trigger: str, output: str, gamepad=None):
        with self._lock:
            self.release_all()
            self.buttons, self.offscreen_trigger, self.output = buttons, offscreen_trigger, output
            if gamepad is not None:
                self.gamepad = gamepad

    def set_user_paused(self, gun_id: int, paused: bool):
        """The player handed the mouse back (phone's MOUSE button): stop moving the cursor and
        clicking for this gun until they hand it back. Separate from calibration pauses so a
        calibration finishing can't switch the mouse back on."""
        with self._lock:
            if paused:
                self.user_paused.add(gun_id)
                for (gid, bit), act in list(self._held.items()):
                    if gid == gun_id:
                        self._do(gid, act, False)
                        del self._held[(gid, bit)]
            else:
                self.user_paused.discard(gun_id)

    def set_enabled(self, on: bool, gun_id: int | None = None):
        with self._lock:
            if gun_id is not None:  # pause just this gun (its calibration), others keep playing
                if on:
                    self.paused_guns.discard(gun_id)
                else:
                    self.paused_guns.add(gun_id)
                    for (gid, bit), act in list(self._held.items()):
                        if gid == gun_id:
                            self._do(gid, act, False)
                            del self._held[(gid, bit)]
                return
            if not on:
                self.release_all()
            self.enabled = on

    def out(self, gid: int) -> str:
        return self.gun_output.get(gid, self.output)

    def release_stale(self, timeout: float = 0.3) -> list[int]:
        """Wi-Fi dropped while a button was held: let go instead of firing forever."""
        now = time.monotonic()
        let_go = []
        with self._lock:
            for gid, t in list(self._last_t.items()):
                if now - t < timeout:
                    continue
                held = [(k, a) for k, a in self._held.items() if k[0] == gid]
                for k, act in held:
                    self._do(gid, act, False)
                    del self._held[k]
                self._offscreen_hold.pop(gid, None)
                if held:
                    let_go.append(gid)
                    log.info("gun %d silent for %.0f ms: released %d held button(s)", gid, (now - t) * 1000, len(held))
                self._prev[gid] = 0
        return let_go

    def _replay_click(self, gid: int, act: str):
        """A whole tap fell into a Wi-Fi gap: press it now, hold one game frame, release."""
        self._do(gid, act, True)
        t = threading.Timer(0.05, lambda: self._do(gid, act, False))
        t.daemon = True
        t.start()

    def release_all(self):
        for (gid, _bit), act in list(self._held.items()):
            self._do(gid, act, False)
        self._held.clear()
        self._offscreen_hold.clear()

    def _refresh_monitor(self):
        """Emulators that switch resolution (exclusive fullscreen) change the monitor rect."""
        try:
            for m in self.backend.monitors():
                if m.name == self.monitor.name:
                    if (m.x, m.y, m.w, m.h) != (self.monitor.x, self.monitor.y, self.monitor.w, self.monitor.h):
                        log.info("monitor geometry changed: %s -> %s", self.monitor, m)
                        self.monitor = m
                    return
        except Exception as e:
            log.debug("monitor refresh failed: %s", e)

    def _do(self, gid: int, act: str, down: bool):
        try:
            self._do_inner(gid, act, down)
        except Exception as e:  # bad mapping in config must not wedge button state
            log.warning("action %r failed: %s", act, e)

    def _do_inner(self, gid: int, act: str, down: bool):
        kind, _, arg = act.partition(":")
        if kind == "none" or not act:
            return
        if kind == "pad":
            if self.gamepad:
                self.gamepad.button(gid, arg, down)
            return
        if kind == "offscreen":
            # point outside the game area, then click; hold the aim there until release
            out = self.out(gid)
            if down:
                self._offscreen_hold[gid] = self._offscreen_hold.get(gid, 0) + 1
                if out in ("mouse", "both"):
                    self.backend.move_abs(gid, self.monitor, 0.0, 0.0)
                if self.gamepad and out != "mouse":
                    self.gamepad.set_aim(gid, 0.0, 0.0, False)
            else:
                n = self._offscreen_hold.get(gid, 1) - 1
                if n <= 0:
                    self._offscreen_hold.pop(gid, None)
                else:
                    self._offscreen_hold[gid] = n
            if ":" in arg:   # "offscreen:key:a" - player 2 on a gamepad presses a key, not the mouse
                self._do_inner(gid, arg, down)
            else:
                self.backend.action(gid, f"mouse:{arg}", down)
            return
        self.backend.action(gid, act, down)

    def update(self, pkt: P.AimPacket):
        with self._lock:
            self.last[pkt.gun_id] = pkt
            now = time.monotonic()
            gap = now - self._last_t.get(pkt.gun_id, now)
            self._last_t[pkt.gun_id] = now
            if now - self._mon_checked > MONITOR_REFRESH_S:
                self._mon_checked = now
                self._refresh_monitor()
            if not self.enabled or pkt.gun_id in self.paused_guns or pkt.gun_id in self.user_paused:
                self._prev[pkt.gun_id] = pkt.buttons
                self._presses[pkt.gun_id] = pkt.presses   # presses while paused (calibration) aren't missed
                return
            out = self.out(pkt.gun_id)
            buttons, offscreen_trigger = self.gun_buttons.get(pkt.gun_id, (self.buttons, self.offscreen_trigger))
            # move first so a click lands where the gun points in this same packet
            if pkt.calibrated and not self._offscreen_hold.get(pkt.gun_id):
                if out in ("mouse", "both"):
                    self.backend.move_abs(pkt.gun_id, self.monitor, pkt.x, pkt.y)
                    m = self.monitor
                    if pkt.gun_id == 0:  # the cursor check follows player 1
                        self.last_move = (now, m.x + min(max(pkt.x, 0.0), 1.0) * (m.w - 1),
                                          m.y + min(max(pkt.y, 0.0), 1.0) * (m.h - 1))
                if out in ("gamepad", "both") and self.gamepad:
                    self.gamepad.set_aim(pkt.gun_id, pkt.x, pkt.y, pkt.on_screen)
            prev = self._prev.get(pkt.gun_id, 0)
            changed = prev ^ pkt.buttons
            # releases before presses
            for down_pass in (False, True):
                if not changed:
                    break
                for name, bit in P.BUTTON_NAMES.items():
                    if not changed & bit or bool(pkt.buttons & bit) != down_pass:
                        continue
                    key = (pkt.gun_id, bit)
                    if down_pass:
                        act = buttons.get(name)
                        if bit == P.BTN_TRIGGER and pkt.calibrated and not pkt.on_screen:
                            act = offscreen_trigger
                        if act:
                            self._held[key] = act
                            self._do(pkt.gun_id, act, True)
                        if self.on_press:
                            try:
                                self.on_press(pkt.gun_id, name, act or "none")
                            except Exception:
                                pass
                    else:
                        act = self._held.pop(key, None)
                        if act:
                            self._do(pkt.gun_id, act, False)
            self._prev[pkt.gun_id] = pkt.buttons
            self._check_presses(pkt, prev, gap, buttons, offscreen_trigger)

    def _check_presses(self, pkt, prev: int, gap: float, buttons: dict, offscreen_trigger: str):
        """The phone counts its presses; if one happened entirely inside lost packets, replay it."""
        gid = pkt.gun_id
        seen = self._presses.get(gid)
        self._presses[gid] = pkt.presses
        if seen is None or pkt.presses == seen:
            return
        for shift, bit, name in ((0, P.BTN_TRIGGER, "trigger"), (4, P.BTN_RELOAD, "reload")):
            phone_n = (pkt.presses >> shift) & 15
            missed = (phone_n - ((seen >> shift) & 15)) & 15
            # presses we saw as an edge in this packet are not missed
            if pkt.buttons & bit and not prev & bit:
                missed -= 1
            # replay only small, recent gaps (a long outage = stale presses, better dropped)
            if 0 < missed <= 2 and gap < 0.5:
                act = buttons.get(name)
                if bit == P.BTN_TRIGGER and pkt.calibrated and not pkt.on_screen:
                    act = offscreen_trigger
                if act:
                    log.info("gun %d: replaying %d %s press(es) lost on Wi-Fi", gid, missed, name)
                    self._replay_click(gid, act)
