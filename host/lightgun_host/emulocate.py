"""Find emulators and their settings files on any machine.

Emulators are found from (1) the window in front, (2) running programs, (3) paths the user
picked with "Choose emulator…". Settings are looked up the way each emulator does it:
portable installs keep them next to the program, installed ones in the user profile.
"""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from pathlib import Path

from .autopreset import preset_for

HOME = Path.home()


def _env_path(var: str) -> Path | None:
    v = os.environ.get(var)
    return Path(v) if v else None


# ---------------------------------------------------------------------------------- RetroArch
def retroarch_cfg_candidates(exe: Path) -> list[Path]:
    c = [exe.parent / "retroarch.cfg"]
    if sys.platform == "win32":
        if _env_path("APPDATA"):
            c.append(_env_path("APPDATA") / "RetroArch" / "retroarch.cfg")
    elif sys.platform == "darwin":
        c.append(HOME / "Library" / "Application Support" / "RetroArch" / "config" / "retroarch.cfg")
        c.append(HOME / "Library" / "Application Support" / "RetroArch" / "retroarch.cfg")
    else:
        xdg = _env_path("XDG_CONFIG_HOME") or HOME / ".config"
        c += [xdg / "retroarch" / "retroarch.cfg",
              HOME / ".var" / "app" / "org.libretro.RetroArch" / "config" / "retroarch" / "retroarch.cfg",
              HOME / "snap" / "retroarch" / "current" / ".config" / "retroarch" / "retroarch.cfg"]
    return c


@dataclass
class RAPaths:
    exe_dir: Path
    cfg: Path
    remaps: Path
    overrides: Path     # per-core / per-game override .cfg and core options .opt
    info: Path
    cores: Path
    playlists: Path


def retroarch_paths(exe: str | Path, read_kv) -> RAPaths | None:
    exe = Path(exe)
    cfg = next((p for p in retroarch_cfg_candidates(exe) if p.exists()), None)
    if cfg is None:
        return None
    kv = read_kv(cfg, "=")
    base = cfg.parent

    def res(key: str, default_sub: str) -> Path:
        v = kv.get(key, "") or "default"
        if v == "default":
            return base / default_sub
        if v.startswith(":"):                       # ':' = RetroArch's own folder
            return exe.parent / v[1:].lstrip("\\/")
        return Path(os.path.expanduser(v))

    return RAPaths(exe.parent, cfg, res("input_remapping_directory", "config/remaps"),
                   res("rgui_config_directory", "config"), res("libretro_info_path", "info"),
                   res("libretro_directory", "cores"), res("playlist_directory", "playlists"))


# ---------------------------------------------------------------------------------- MAME
def mame_ini_candidates(exe: Path) -> list[Path]:
    c = [exe.parent / "mame.ini", exe.parent / "ini" / "mame.ini"]
    if sys.platform != "win32":
        c += [HOME / ".mame" / "mame.ini", HOME / ".mame" / "ini" / "mame.ini"]
    return c


def mame_ini(exe: str | Path) -> Path | None:
    return next((p for p in mame_ini_candidates(Path(exe)) if p.exists()), None)


def mame_ini_target(exe: str | Path) -> Path:
    """Where a new mame.ini should go (MAME reads it from its working folder / ~/.mame)."""
    exe = Path(exe)
    return exe.parent / "mame.ini" if sys.platform == "win32" else HOME / ".mame" / "mame.ini"


# ---------------------------------------------------------------------------------- programs
def running_exes() -> list[str]:
    """Full paths of running programs (best effort, no admin rights needed)."""
    out = []
    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes
        psapi, k32 = ctypes.WinDLL("psapi"), ctypes.windll.kernel32
        arr = (wintypes.DWORD * 4096)()
        needed = wintypes.DWORD()
        if not psapi.EnumProcesses(arr, ctypes.sizeof(arr), ctypes.byref(needed)):
            return out
        for pid in arr[: needed.value // ctypes.sizeof(wintypes.DWORD)]:
            h = k32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
            if not h:
                continue
            try:
                buf = ctypes.create_unicode_buffer(1024)
                n = wintypes.DWORD(len(buf))
                if k32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)):
                    out.append(buf.value)
            finally:
                k32.CloseHandle(h)
    elif sys.platform.startswith("linux"):
        for d in Path("/proc").iterdir():
            if d.name.isdigit():
                try:
                    out.append(os.readlink(d / "exe"))
                except OSError:
                    pass
    return out


def running_emulators() -> list[str]:
    return [p for p in running_exes() if preset_for(p)]
