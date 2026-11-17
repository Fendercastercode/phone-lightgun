"""Linux backend: one uinput absolute-pointer device per gun (multi-gun capable)."""
from __future__ import annotations

import os

from evdev import AbsInfo, UInput, ecodes as e  # type: ignore

from . import Backend, Monitor

ABS_MAX = 65535
MOUSE = {"left": e.BTN_LEFT, "right": e.BTN_RIGHT, "middle": e.BTN_MIDDLE}
KEYS = {"enter": e.KEY_ENTER, "esc": e.KEY_ESC, "backspace": e.KEY_BACKSPACE,
        "rshift": e.KEY_RIGHTSHIFT, "lshift": e.KEY_LEFTSHIFT, "lctrl": e.KEY_LEFTCTRL, "rctrl": e.KEY_RIGHTCTRL,
        "lalt": e.KEY_LEFTALT, "ralt": e.KEY_RIGHTALT, "space": e.KEY_SPACE, "tab": e.KEY_TAB,
        "up": e.KEY_UP, "down": e.KEY_DOWN, "left": e.KEY_LEFT, "right": e.KEY_RIGHT,
        **{f"f{i}": getattr(e, f"KEY_F{i}") for i in range(1, 13)}}


def _key(name):
    name = name.lower()
    if name in KEYS:
        return KEYS[name]
    return getattr(e, f"KEY_{name.upper()}")


UINPUT_HELP = ("This PC won't let RetroSight create its virtual light gun (/dev/uinput). "
               "Run ./start-host.sh once - it offers to fix this (it needs your password once), "
               "then log out and back in.")


def uinput_ok() -> bool:
    return os.path.exists("/dev/uinput") and os.access("/dev/uinput", os.W_OK)


class LinuxBackend(Backend):
    def __init__(self):
        if not uinput_ok():
            raise PermissionError(UINPUT_HELP)
        self.devs: dict[int, UInput] = {}
        self._rects: dict[str, tuple] = {}      # monitor id -> (x, y, w, h, dpr) in desktop (logical) units
        self._desk = (0, 0, 1, 1)               # bounding box of all monitors
        self._dev(0)                            # fail now, not on the first shot

    def _dev(self, gid: int) -> UInput:
        d = self.devs.get(gid)
        if d is None:
            caps = {
                e.EV_ABS: [(e.ABS_X, AbsInfo(0, 0, ABS_MAX, 0, 0, 0)),
                           (e.ABS_Y, AbsInfo(0, 0, ABS_MAX, 0, 0, 0))],
                e.EV_KEY: list(MOUSE.values()) + list(KEYS.values())
                + [getattr(e, f"KEY_{c}") for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"],
            }
            # "Lightgun" in the name lets Batocera/RetroArch-udev style tools spot it as a gun
            d = self.devs[gid] = UInput(caps, name=f"Phone Lightgun {gid + 1}",
                                        input_props=[e.INPUT_PROP_DIRECT])
        return d

    def monitors(self):
        # A uinput absolute device spans the whole X/Wayland screen; per-monitor
        # mapping is done by the compositor (xinput map-to-output). Report one.
        try:
            from PySide6.QtGui import QGuiApplication
            app = QGuiApplication.instance()
            if app:
                out = []
                self._rects = {}
                for i, s in enumerate(app.screens()):
                    g = s.geometry()
                    r = s.devicePixelRatio()
                    mid = f"{s.name()}:{int(g.width()*r)}x{int(g.height()*r)}"
                    self._rects[mid] = (g.x(), g.y(), g.width(), g.height(), r)
                    out.append(Monitor(mid, s.name(), g.x(), g.y(), int(g.width() * r), int(g.height() * r),
                                       s == app.primaryScreen()))
                if self._rects:
                    xs = [(x, x + w) for x, _, w, _, _ in self._rects.values()]
                    ys = [(y, y + h) for _, y, _, h, _ in self._rects.values()]
                    self._desk = (min(a for a, _ in xs), min(a for a, _ in ys), max(b for _, b in xs), max(b for _, b in ys))
                return out
        except ImportError:
            pass
        return [Monitor("default", "default", 0, 0, 1920, 1080, True)]

    def desktop_fraction(self, mon, x, y):
        """The absolute device spans the whole desktop: map a point on [mon] to a fraction of it."""
        x = min(max(x, 0.0), 1.0); y = min(max(y, 0.0), 1.0)
        r = self._rects.get(mon.id)
        if r is None:
            return x, y
        mx, my, mw, mh, _ = r
        x0, y0, x1, y1 = self._desk
        return (mx + x * mw - x0) / max(x1 - x0, 1), (my + y * mh - y0) / max(y1 - y0, 1)

    def move_abs(self, gun_id, mon, x, y):
        d = self._dev(gun_id)
        fx, fy = self.desktop_fraction(mon, x, y)
        d.write(e.EV_ABS, e.ABS_X, int(fx * ABS_MAX))
        d.write(e.EV_ABS, e.ABS_Y, int(fy * ABS_MAX))
        d.syn()

    def cursor_pos(self):
        # same units as Monitor (origin logical, size physical) so the cursor check can compare
        try:
            from PySide6.QtGui import QCursor, QGuiApplication
            if QGuiApplication.instance() is None or QGuiApplication.platformName() == "wayland":
                return None   # Wayland doesn't tell apps where the cursor is
            p = QCursor.pos()
            for x, y, w, h, r in self._rects.values():
                if x <= p.x() < x + w and y <= p.y() < y + h:
                    return x + (p.x() - x) * r, y + (p.y() - y) * r
        except Exception:
            pass
        return None

    def action(self, gun_id, action, down):
        kind, _, arg = action.partition(":")
        code = MOUSE[arg] if kind == "mouse" else _key(arg)
        d = self._dev(gun_id)
        d.write(e.EV_KEY, code, 1 if down else 0)
        d.syn()

    def close(self):
        for d in self.devs.values():
            d.close()
