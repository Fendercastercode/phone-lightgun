"""Virtual gamepad output: aim on the right stick (absolute), one pad per gun.

Windows: ViGEmBus driver + `pip install vgamepad` (Xbox 360 pad).
Linux:   uinput gamepad via python-evdev (no extra driver).
"""
from __future__ import annotations

import logging
import sys

log = logging.getLogger(__name__)

PAD_BUTTONS = ("a", "b", "x", "y", "lb", "rb", "lt", "rt", "start", "back", "ls", "rs")


class Gamepad:
    def set_aim(self, gun_id: int, x: float, y: float, on_screen: bool):
        raise NotImplementedError

    def button(self, gun_id: int, name: str, down: bool):
        raise NotImplementedError

    def close(self):
        pass


class NullGamepad(Gamepad):
    def __init__(self):
        self.events = []

    def set_aim(self, gun_id, x, y, on_screen):
        self.events.append(("aim", gun_id, x, y))

    def button(self, gun_id, name, down):
        self.events.append(("pad", gun_id, name, down))


def _stick(x: float, y: float):
    """Normalized screen (0..1, y down) -> stick (-1..1, y up)."""
    return max(-1.0, min(1.0, x * 2 - 1)), max(-1.0, min(1.0, 1 - y * 2))


class ViGEmGamepad(Gamepad):
    def __init__(self):
        import vgamepad as vg  # type: ignore
        self.vg = vg
        self.pads = {}
        B = vg.XUSB_BUTTON
        self.map = {"a": B.XUSB_GAMEPAD_A, "b": B.XUSB_GAMEPAD_B, "x": B.XUSB_GAMEPAD_X,
                    "y": B.XUSB_GAMEPAD_Y, "lb": B.XUSB_GAMEPAD_LEFT_SHOULDER,
                    "rb": B.XUSB_GAMEPAD_RIGHT_SHOULDER, "start": B.XUSB_GAMEPAD_START,
                    "back": B.XUSB_GAMEPAD_BACK, "ls": B.XUSB_GAMEPAD_LEFT_THUMB,
                    "rs": B.XUSB_GAMEPAD_RIGHT_THUMB}

    def _pad(self, gid):
        p = self.pads.get(gid)
        if p is None:
            p = self.pads[gid] = self.vg.VX360Gamepad()
        return p

    def set_aim(self, gun_id, x, y, on_screen):
        p = self._pad(gun_id)
        sx, sy = _stick(x, y)
        p.right_joystick_float(x_value_float=sx, y_value_float=sy)
        p.update()

    def button(self, gun_id, name, down):
        p = self._pad(gun_id)
        if name in ("lt", "rt"):
            (p.left_trigger if name == "lt" else p.right_trigger)(value=255 if down else 0)
        else:
            (p.press_button if down else p.release_button)(button=self.map[name])
        p.update()


class UinputGamepad(Gamepad):
    def __init__(self):
        from evdev import AbsInfo, UInput, ecodes as e  # type: ignore
        self.e, self.UInput, self.AbsInfo = e, UInput, AbsInfo
        self.devs = {}
        self.map = {"a": e.BTN_SOUTH, "b": e.BTN_EAST, "x": e.BTN_WEST, "y": e.BTN_NORTH,
                    "lb": e.BTN_TL, "rb": e.BTN_TR, "lt": e.BTN_TL2, "rt": e.BTN_TR2,
                    "start": e.BTN_START, "back": e.BTN_SELECT, "ls": e.BTN_THUMBL, "rs": e.BTN_THUMBR}

    def _dev(self, gid):
        d = self.devs.get(gid)
        if d is None:
            e, A = self.e, self.AbsInfo
            ax = A(0, -32768, 32767, 0, 0, 0)
            caps = {e.EV_KEY: list(self.map.values()),
                    e.EV_ABS: [(e.ABS_X, ax), (e.ABS_Y, ax), (e.ABS_RX, ax), (e.ABS_RY, ax)]}
            d = self.devs[gid] = self.UInput(caps, name=f"Phone Light Gun Pad {gid + 1}")
        return d

    def set_aim(self, gun_id, x, y, on_screen):
        d = self._dev(gun_id)
        sx, sy = _stick(x, y)
        d.write(self.e.EV_ABS, self.e.ABS_RX, int(sx * 32767))
        d.write(self.e.EV_ABS, self.e.ABS_RY, int(-sy * 32767))  # evdev Y axis points down
        d.syn()

    def button(self, gun_id, name, down):
        d = self._dev(gun_id)
        d.write(self.e.EV_KEY, self.map[name], 1 if down else 0)
        d.syn()

    def close(self):
        for d in self.devs.values():
            d.close()


def make_gamepad() -> Gamepad | None:
    try:
        if sys.platform == "win32":
            return ViGEmGamepad()
        if sys.platform.startswith("linux"):
            return UinputGamepad()
    except Exception as ex:
        log.warning("virtual gamepad unavailable: %s (Windows: install ViGEmBus + pip install vgamepad)", ex)
    return None
