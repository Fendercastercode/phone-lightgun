"""Windows backend: SendInput with MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK."""
from __future__ import annotations

import ctypes
from ctypes import wintypes

from . import Backend, Monitor

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
MOUSEEVENTF_VIRTUALDESK = 0x4000
MOUSEEVENTF_ABSOLUTE = 0x8000
KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_SCANCODE = 0x0001, 0x0002, 0x0008
MAPVK_VK_TO_VSC_EX = 4
# keys whose scan code needs the E0 prefix
EXTENDED_VK = {0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x24, 0x23, 0x21, 0x22, 0xA3, 0xA5}
SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 76, 77, 78, 79
MONITORINFOF_PRIMARY = 1

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


class MONITORINFOEXW(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT),
                ("dwFlags", wintypes.DWORD), ("szDevice", wintypes.WCHAR * 32)]


user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.GetMonitorInfoW.argtypes = [wintypes.HMONITOR, ctypes.POINTER(MONITORINFOEXW)]
MONITORENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMONITOR, wintypes.HDC,
                                     ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)

MOUSE_FLAGS = {
    "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}

VK = {"enter": 0x0D, "esc": 0x1B, "space": 0x20, "tab": 0x09, "backspace": 0x08,
      "shift": 0x10, "rshift": 0xA1, "lshift": 0xA0, "rctrl": 0xA3, "lctrl": 0xA2, "lalt": 0xA4, "ralt": 0xA5, "ctrl": 0x11, "alt": 0x12, "left": 0x25, "up": 0x26, "right": 0x27,
      "down": 0x28, **{f"f{i}": 0x6F + i for i in range(1, 13)}}


def _vk(name: str) -> int:
    name = name.lower()
    if name in VK:
        return VK[name]
    if len(name) == 1 and name.isalnum():
        return ord(name.upper())
    raise ValueError(f"unknown key {name!r}")


def set_dpi_aware():
    try:
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # PER_MONITOR_AWARE_V2
    except (AttributeError, OSError):
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except (AttributeError, OSError):
            user32.SetProcessDPIAware()


class WindowsBackend(Backend):
    def __init__(self):
        set_dpi_aware()
        self._inp = INPUT()
        self._inp.type = INPUT_MOUSE

    def monitors(self):
        mons = []

        def cb(hmon, _hdc, _rect, _lp):
            info = MONITORINFOEXW()
            info.cbSize = ctypes.sizeof(MONITORINFOEXW)
            if user32.GetMonitorInfoW(hmon, ctypes.byref(info)):
                r = info.rcMonitor
                w, h = r.right - r.left, r.bottom - r.top
                dev = info.szDevice
                mons.append(Monitor(f"{dev}:{w}x{h}", dev, r.left, r.top, w, h,
                                    bool(info.dwFlags & MONITORINFOF_PRIMARY)))
            return True

        user32.EnumDisplayMonitors(None, None, MONITORENUMPROC(cb), 0)
        mons.sort(key=lambda m: (not m.primary, m.x))
        return mons

    def _send(self, inp: INPUT):
        user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    def move_abs(self, gun_id, mon, x, y):
        vx = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
        vy = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
        vw = max(1, user32.GetSystemMetrics(SM_CXVIRTUALSCREEN) - 1)
        vh = max(1, user32.GetSystemMetrics(SM_CYVIRTUALSCREEN) - 1)
        px = mon.x + min(max(x, 0.0), 1.0) * (mon.w - 1)
        py = mon.y + min(max(y, 0.0), 1.0) * (mon.h - 1)
        mi = self._inp.u.mi
        mi.dx = int(round((px - vx) * 65535 / vw))
        mi.dy = int(round((py - vy) * 65535 / vh))
        mi.mouseData = 0
        mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK
        self._send(self._inp)

    def cursor_pos(self):
        pt = wintypes.POINT()
        return (pt.x, pt.y) if user32.GetCursorPos(ctypes.byref(pt)) else None

    def action(self, gun_id, action, down):
        kind, _, arg = action.partition(":")
        if kind == "mouse":
            inp = INPUT()
            inp.type = INPUT_MOUSE
            inp.u.mi.dwFlags = MOUSE_FLAGS[arg][0 if down else 1]
            self._send(inp)
        elif kind == "key":
            inp = INPUT()
            inp.type = INPUT_KEYBOARD
            vk = _vk(arg)
            # Send the hardware scan code too: emulators that read the keyboard through
            # DirectInput / Raw Input (MAME, RetroArch "raw") ignore virtual-key-only events.
            sc = user32.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC_EX) & 0xFF
            flags = KEYEVENTF_SCANCODE if sc else 0
            if vk in EXTENDED_VK:
                flags |= KEYEVENTF_EXTENDEDKEY
            inp.u.ki.wVk = vk
            inp.u.ki.wScan = sc
            inp.u.ki.dwFlags = flags | (0 if down else KEYEVENTF_KEYUP)
            self._send(inp)
