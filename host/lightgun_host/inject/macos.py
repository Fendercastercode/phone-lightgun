"""macOS backend via Quartz CGEventPost (pip install pyobjc-framework-Quartz)."""
from __future__ import annotations

import Quartz  # type: ignore

from . import Backend, Monitor

_MOUSE = {
    "left": (Quartz.kCGEventLeftMouseDown, Quartz.kCGEventLeftMouseUp, Quartz.kCGEventLeftMouseDragged, 0),
    "right": (Quartz.kCGEventRightMouseDown, Quartz.kCGEventRightMouseUp, Quartz.kCGEventRightMouseDragged, 1),
    "middle": (Quartz.kCGEventOtherMouseDown, Quartz.kCGEventOtherMouseUp, Quartz.kCGEventOtherMouseDragged, 2),
}
_KEYS = {"enter": 36, "esc": 53, "space": 49, "tab": 48, "backspace": 51, "rshift": 60, "r": 15,
         "1": 18, "2": 19, "5": 23, "left": 123, "right": 124, "down": 125, "up": 126}


class MacBackend(Backend):
    def __init__(self):
        self.pos = (0.0, 0.0)
        self.down: set[str] = set()

    def monitors(self):
        err, ids, cnt = Quartz.CGGetActiveDisplayList(16, None, None)
        out = []
        for did in ids[:cnt]:
            b = Quartz.CGDisplayBounds(did)
            out.append(Monitor(str(did), f"display{did}", int(b.origin.x), int(b.origin.y),
                               int(b.size.width), int(b.size.height), did == Quartz.CGMainDisplayID()))
        return out

    def move_abs(self, gun_id, mon, x, y):
        self.pos = (mon.x + min(max(x, 0), 1) * (mon.w - 1), mon.y + min(max(y, 0), 1) * (mon.h - 1))
        etype, btn = Quartz.kCGEventMouseMoved, 0
        for name in ("left", "right", "middle"):
            if name in self.down:
                etype, btn = _MOUSE[name][2], _MOUSE[name][3]
                break
        ev = Quartz.CGEventCreateMouseEvent(None, etype, self.pos, btn)
        Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev)

    def action(self, gun_id, action, down):
        kind, _, arg = action.partition(":")
        if kind == "mouse":
            d, u, _drag, btn = _MOUSE[arg]
            (self.down.add if down else self.down.discard)(arg)
            ev = Quartz.CGEventCreateMouseEvent(None, d if down else u, self.pos, btn)
        else:
            ev = Quartz.CGEventCreateKeyboardEvent(None, _KEYS[arg.lower()], down)
        Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev)
