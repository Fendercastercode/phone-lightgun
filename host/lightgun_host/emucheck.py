""""Is the emulator seeing the gun?" - settings checks for the emulator in front.

Reads the emulator's own config files (read-only) and reports what would stop it from
reading the gun, with the fix in plain words. Nothing here changes the emulator.
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

from . import emulocate
from .autopreset import preset_for

LIGHTGUN_CLASS = 4  # RETRO_DEVICE_LIGHTGUN; libretro subclass ids keep it in the low byte


@dataclass
class Check:
    ok: bool
    text: str          # what was checked, e.g. "lightgunprovider = win32"
    fix: str = ""      # what to do when not ok


@dataclass
class EmuStatus:
    name: str                       # "MAME", "RetroArch", ... or "" when no emulator is in front
    exe: str = ""
    preset: str | None = None
    game: str = ""
    checks: list[Check] = field(default_factory=list)

    @property
    def problems(self) -> list[Check]:
        return [c for c in self.checks if not c.ok]

    def to_msg(self) -> dict:
        return {"type": "emu_status", "emu": self.name, "game": self.game, "preset": self.preset,
                "checks": [{"ok": c.ok, "text": c.text, "fix": c.fix} for c in self.checks]}


def _read_kv(path: Path, sep: str | None) -> dict:
    """MAME ini (whitespace separated) or RetroArch cfg (key = "value")."""
    out = {}
    try:
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if sep:
                if sep not in line:
                    continue
                k, v = line.split(sep, 1)
                out[k.strip()] = v.strip().strip('"')
            else:
                parts = line.split(None, 1)
                out[parts[0]] = parts[1].strip() if len(parts) > 1 else ""
    except OSError:
        pass
    return out


# ---------------------------------------------------------------------------------- MAME
def check_mame(exe: Path) -> EmuStatus:
    st = EmuStatus("MAME", str(exe), "mame")
    ini = emulocate.mame_ini(exe)
    if ini is None:
        st.checks.append(Check(False, "mame.ini not found",
                               "Press 'Fix emulator settings' to create it with the gun options"))
        return st
    o = _read_kv(ini, None)
    st.checks.append(Check(o.get("lightgun") == "1", f"lightgun = {o.get('lightgun', '0')}",
                           "Set 'lightgun 1' in mame.ini (MAME ignores guns otherwise)"))
    dev = o.get("lightgun_device", "lightgun")
    st.checks.append(Check(dev in ("lightgun", "mouse"), f"lightgun_device = {dev}",
                           "Set 'lightgun_device lightgun' in mame.ini"))
    if sys.platform == "win32":
        prov = o.get("lightgunprovider", "rawinput")
        st.checks.append(Check(prov == "win32", f"lightgunprovider = {prov}",
                               "Set 'lightgunprovider win32' in mame.ini - 'rawinput' ignores the phone. "
                               "MAME's 'Save Configuration' resets it, so check again after saving"))
    if sys.platform.startswith("linux"):
        prov = o.get("lightgunprovider", "auto")
        st.checks.append(Check(prov in ("x11", "auto"), f"lightgunprovider = {prov}",
                               "Set 'lightgunprovider x11' in mame.ini"))
        for i in (1, 2):
            name = o.get(f"lightgun_index{i}", "auto").strip('"')
            st.checks.append(Check(name == f"Phone Lightgun {i}", f"lightgun_index{i} = {name}",
                                   f"MAME only uses guns listed by name: set lightgun_index{i} \"Phone Lightgun {i}\""))
        if os.environ.get("WAYLAND_DISPLAY"):
            st.checks.append(Check(False, "desktop is Wayland",
                                   "MAME guns work best in an X11 session: log out, pick 'Xorg' or "
                                   "'X11' on the login screen (gear icon), log back in"))
    st.checks.append(Check(True, "Coin = key 5, Start = key 1 (MAME defaults)"))
    return st


# ---------------------------------------------------------------------------------- RetroArch
def _ra_core_name(info_dir: Path, core_path: str) -> str:
    """Library name used for remap/override folders, e.g. 'Genesis Plus GX'."""
    stem = Path(core_path.replace("\\", "/")).stem
    info = _read_kv(info_dir / f"{stem}.info", "=")
    return info.get("corename", "")


def _ra_last_content(playlists: Path):
    for p in (playlists / "builtin" / "content_history.lpl", playlists / "content_history.lpl",
              playlists.parent / "content_history.lpl"):
        try:
            items = json.loads(p.read_text(encoding="utf-8")).get("items", [])
        except (OSError, ValueError):
            continue
        if items:
            return items[0].get("path", ""), items[0].get("core_path", "")
    return "", ""


def check_retroarch(exe: Path) -> EmuStatus:
    st = EmuStatus("RetroArch", str(exe), "retroarch")
    rp = emulocate.retroarch_paths(exe, _read_kv)
    if rp is None:
        st.checks.append(Check(False, "retroarch.cfg not found",
                               "Start RetroArch once and quit it (it creates its settings), or use 'Choose emulator…'"))
        return st
    cfg = _read_kv(rp.cfg, "=")
    if sys.platform == "win32":
        drv = cfg.get("input_driver", "")
        # "raw" drops mouse input made by programs (it only reads real devices): the phone needs dinput
        st.checks.append(Check(drv == "dinput", f"input driver = {drv or '?'}",
                               "Settings > Drivers > Input = dinput (raw ignores the phone's mouse)"))
    content, core_path = _ra_last_content(rp.playlists)
    core = _ra_core_name(rp.info, core_path) if core_path else ""
    if not content or not core:
        st.checks.append(Check(False, "no game loaded yet", "Load a game, then look here again"))
        return st
    game = Path(content.replace("\\", "/")).stem
    st.game = game
    # settings layered like RetroArch: main cfg < core override < game override; remaps likewise
    merged = dict(cfg)
    for f in (rp.overrides / core / f"{core}.cfg", rp.overrides / core / f"{game}.cfg"):
        merged.update(_read_kv(f, "="))
    remaps_dir = rp.remaps
    remap = {}
    for f in (remaps_dir / core / f"{core}.rmp", remaps_dir / core / f"{game}.rmp"):
        remap.update(_read_kv(f, "="))
    devices = {}
    for n in range(1, 5):
        v = remap.get(f"input_libretro_device_p{n}", merged.get(f"input_libretro_device_p{n}"))
        try:
            devices[n] = int(v)
        except (TypeError, ValueError):
            pass
    gun_ports = [n for n, d in devices.items() if d & 0xFF == LIGHTGUN_CLASS]
    if devices and len(gun_ports) < len(devices):
        # some cores (Mesen's Zapper, FCEUmm's Zapper...) file their gun under a device class
        # other than RETRO_DEVICE_LIGHTGUN; fall back to matching the core's own name for it
        from .emufix import core_ports, looks_like_gun_device
        dll = Path(core_path.replace("\\", "/"))
        if not dll.exists():
            dll = rp.cores / dll.name
        by_id = {t["id"]: t["desc"] for p in core_ports(dll) for t in p}
        gun_ports += [n for n, d in devices.items()
                      if n not in gun_ports and looks_like_gun_device(by_id.get(d, ""))]
    if core.strip().upper() in ("MAME", "HBMAME"):
        # the MAME core has no gun device type: its "Lightgun Mode" option turns the gun on (port 1)
        mode = _read_kv(rp.overrides / core / f"{core}.opt", "=").get("mame_lightgun_mode", "none")
        st.checks.append(Check(mode == "lightgun", f"MAME core Lightgun Mode = {mode}",
                               "Quick Menu > Core Options > Input > Lightgun Mode = lightgun"))
        gun_ports = [1]
    if not gun_ports:
        st.checks.append(Check(False, f"{game}: no port set to a light gun",
                               "In the game: Quick Menu > Controls > Port 1 (Genesis Menacer/Justifier: "
                               "Port 2) > Device Type = the gun, then Manage Remap Files > Save Game Remap File"))
        return st
    if len(gun_ports) > 1:
        st.checks.append(Check(False, f"ports {', '.join(map(str, gun_ports))} are all set to light guns",
                               f"Only the port the game reads should be a gun (Genesis: port 2, Master System: "
                               f"port 1). Set the other one back to a joypad and save the game remap"))
    for n in gun_ports:
        st.checks.append(Check(True, f"{game}: port {n} is a light gun (saved)"))
        idx = merged.get(f"input_player{n}_mouse_index", "0")
        st.checks.append(Check(idx == "0", f"port {n} mouse index = {idx}",
                               f"Settings > Input > Port {n} Controls > Mouse Index = 0"))
        trig = merged.get(f"input_player{n}_gun_trigger_mbtn", "nul")
        st.checks.append(Check(trig not in ("nul", ""), f"port {n} gun trigger = mouse {trig}",
                               f"Port {n} Controls > Gun Trigger = left mouse button"))
        start = merged.get(f"input_player{n}_gun_start", "nul")
        start_m = merged.get(f"input_player{n}_gun_start_mbtn", "nul")
        st.checks.append(Check(start not in ("nul", "") or start_m not in ("nul", ""),
                               f"port {n} gun start = {start if start != 'nul' else 'mouse ' + start_m}",
                               f"Port {n} Controls > Gun Start = Enter (many games wait for Start)"))
    return st


# ---------------------------------------------------------------------------------- entry
def _check_inisec(name: str, preset: str, exe: Path, ini: Path, want: dict) -> EmuStatus:
    from .emufix import read_inisec
    st = EmuStatus(name, str(exe), preset)
    have = read_inisec(ini)
    if not have:
        st.checks.append(Check(False, f"{ini.name} not found", f"Start {name} once, close it, then press 'Fix emulator settings'"))
        return st
    for k, v in want.items():
        st.checks.append(Check(have.get(k) == v, f"{k.split('/', 1)[1]} = {have.get(k, 'not set')}",
                               "Press 'Fix emulator settings' (with the emulator closed)"))
    return st


def check_duckstation(exe: Path) -> EmuStatus:
    from .emufix import DUCKSTATION_GUN, duckstation_ini
    return _check_inisec("DuckStation", "duckstation", exe, duckstation_ini(str(exe)), DUCKSTATION_GUN)


def check_pcsx2(exe: Path) -> EmuStatus:
    from .emufix import PCSX2_GUN, pcsx2_ini
    return _check_inisec("PCSX2", "pcsx2", exe, pcsx2_ini(str(exe)), PCSX2_GUN)


CHECKERS = {"mame": check_mame, "retroarch": check_retroarch, "duckstation": check_duckstation, "pcsx2": check_pcsx2}
NAMES = {"duckstation": "DuckStation", "pcsx2": "PCSX2", "flycast": "Flycast", "dolphin": "Dolphin",
         "model2_supermodel": "Model 2 / Supermodel", "teknoparrot_demulshooter": "TeknoParrot"}


def check(exe: str | None) -> EmuStatus | None:
    """Status for the emulator at [exe], or None if it isn't a known emulator."""
    preset = preset_for(exe)
    if preset is None:
        return None
    fn = CHECKERS.get(preset)
    if fn:
        try:
            st = fn(Path(exe))
        except Exception as e:  # never let a odd config file break the host
            return EmuStatus(preset, exe, preset, checks=[Check(False, f"could not read settings: {e}")])
    else:
        name = NAMES.get(preset, preset)
        if preset == "model2_supermodel":
            name = "Supermodel" if os.path.basename(exe).lower().startswith("supermodel") else "Sega Model 2"
        st = EmuStatus(name, exe, preset, checks=[])
    _add_binding_checks(st)
    if not st.checks:
        st.checks.append(Check(True, "no settings check for this emulator yet - use the button echo"))
    return st


def _add_binding_checks(st: EmuStatus):
    """What the emulator's own settings say about the phone's buttons (see bindread)."""
    from . import bindread
    live = bindread.read(st.preset, st.exe)
    if live is None:
        return
    if live.unbound:
        st.checks.append(Check(False, "nothing the phone can press for " + ", ".join(live.unbound),
                               "Fix emulator settings adds it (your own bindings are kept)"))
    if live.conflicts:
        st.checks.append(Check(False, "one phone button does two things: " + "; ".join(live.conflicts),
                               "Fix emulator settings gives each its own key"))
    if not live.unbound and not live.conflicts:
        st.checks.append(Check(True, "phone buttons match " + live.emulator + "'s settings"))
