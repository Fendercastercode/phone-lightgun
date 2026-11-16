"""Pick the emulator preset from the program whose window is in front.

Only the executable name and window title are looked at; nothing is read from the other program.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys

# executable name prefix (lower case, no extension) -> preset key. First match wins.
EXE_PRESETS: list[tuple[str, str]] = [
    ("retroarch", "retroarch"),
    ("groovymame", "mame"),
    ("mameui", "mame"),
    ("mame", "mame"),
    ("duckstation", "duckstation"),
    ("pcsx2", "pcsx2"),
    ("flycast", "flycast"),
    ("dolphin", "dolphin"),
    ("mesen", "mesen"),
    ("supermodel", "model2_supermodel"),
    ("emulator_multicpu", "model2_supermodel"),   # Sega Model 2 Emulator
    ("emulator", "model2_supermodel"),
    ("teknoparrot", "teknoparrot_demulshooter"),
    ("budgieloader", "teknoparrot_demulshooter"),
    ("openparrot", "teknoparrot_demulshooter"),
    ("demulshooter", "teknoparrot_demulshooter"),
]


def preset_for(exe: str | None) -> str | None:
    if not exe:
        return None
    # os.path.basename only understands the running OS's own separator (posixpath doesn't
    # split on "\\"); normalize first so this matches a Windows-style exe path consistently
    # regardless of which OS is actually running the code (real paths from a Linux/Pi build
    # never contain "\\" anyway, so this is a no-op there).
    name = os.path.splitext(os.path.basename(exe.replace("\\", "/")))[0].lower()
    for prefix, preset in EXE_PRESETS:
        if name.startswith(prefix):
            return preset
    return None


def _foreground_windows() -> str | None:
    import ctypes
    from ctypes import wintypes
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    hwnd = user32.GetForegroundWindow()
    if not hwnd:
        return None
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    h = kernel32.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return None
    try:
        buf = ctypes.create_unicode_buffer(1024)
        n = wintypes.DWORD(len(buf))
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)):
            return buf.value
        return None
    finally:
        kernel32.CloseHandle(h)


def _foreground_linux() -> str | None:
    if not shutil.which("xdotool"):
        return None
    try:
        pid = subprocess.run(["xdotool", "getactivewindow", "getwindowpid"], capture_output=True,
                             text=True, timeout=0.5).stdout.strip()
        return os.readlink(f"/proc/{int(pid)}/exe") if pid else None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def foreground_title() -> str | None:
    """Title of the window in front (MAME puts the game's short name in it: '... [area51]')."""
    try:
        if sys.platform == "win32":
            import ctypes
            user32 = ctypes.windll.user32
            hwnd = user32.GetForegroundWindow()
            n = user32.GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(n + 1)
            user32.GetWindowTextW(hwnd, buf, n + 1)
            return buf.value or None
        if sys.platform.startswith("linux") and shutil.which("xdotool"):
            return subprocess.run(["xdotool", "getactivewindow", "getwindowname"], capture_output=True,
                                  text=True, timeout=0.5).stdout.strip() or None
    except Exception:
        return None
    return None


def foreground_exe() -> str | None:
    """Full path of the program owning the foreground window, or None if unknown."""
    try:
        if sys.platform == "win32":
            return _foreground_windows()
        if sys.platform.startswith("linux"):
            exe = _foreground_linux()
            if exe is None:
                # Wayland or no xdotool: can't see the front window - if exactly one emulator runs, it's that one
                from .emulocate import running_emulators
                emus = running_emulators()
                if len(set(emus)) == 1:
                    return emus[0]
            return exe
    except Exception:
        return None
    return None
