"""Read what each emulator's buttons are really bound to, from its own settings files.

The phone's buttons then send exactly what the emulator listens for, rather than a table of defaults
that a new emulator build could change. Where a setting is missing from the files, the emulator's
built-in default applies. For MAME those defaults come from the source code of the exact MAME version
that's installed (downloaded once per version and cached), with a built-in copy as the fallback.

    read(preset, exe, title) -> Live | None
"""
from __future__ import annotations

import json
import logging
import re
import subprocess
import sys
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from . import emucheck, emulocate

log = logging.getLogger(__name__)

PHONE = ("trigger", "reload", "a", "b", "start", "select")


@dataclass
class Live:
    emulator: str
    preset: str
    buttons: dict                      # player 1: phone button -> action
    offscreen: str                     # what the trigger does when fired off-screen
    labels: dict                       # phone button -> what it is in the emulator
    p2: tuple | None = None            # player 2: (buttons, offscreen, labels)
    unbound: list = field(default_factory=list)   # emulator functions with nothing the phone can press
    conflicts: list = field(default_factory=list) # "key X is also <other binding>": one press does two things
    sources: list = field(default_factory=list)   # where the bindings came from
    detail: dict = field(default_factory=dict)    # phone button -> the emulator's own binding text

    def key(self) -> str:
        return json.dumps([self.preset, self.buttons, self.offscreen, self.p2], sort_keys=True)

    def summary(self) -> str:
        names = {"trigger": "Trigger", "reload": "Reload", "a": "A", "b": "B", "start": "ST", "select": "COIN"}
        return ", ".join(f"{names[b]} = {self.labels.get(b, '-')}" for b in PHONE)


def _offscreen(trigger: str) -> str:
    """Reload = the trigger pulled with the gun pointed off the screen."""
    if trigger.startswith("mouse:") and trigger != "mouse:middle":
        return "offscreen:" + trigger[6:]
    if trigger.startswith("key:"):
        return "offscreen:" + trigger
    return trigger


# ============================================================================================ MAME
# MAME's own defaults (mamedev/mame src/emu/inpttype.ipp, mame0289). Used until the installed
# version's defaults have been fetched, or if that can't be done (offline).
MAME_BUILTIN = {
    "P1_BUTTON1": "KEYCODE_LCONTROL OR MOUSECODE_1_BUTTON1 OR GUNCODE_1_BUTTON1",
    "P1_BUTTON2": "KEYCODE_LALT OR MOUSECODE_1_BUTTON3 OR GUNCODE_1_BUTTON2",
    "P1_BUTTON3": "KEYCODE_SPACE OR MOUSECODE_1_BUTTON2",
    "START1": "KEYCODE_1 OR JOYCODE_1_START",
    "COIN1": "KEYCODE_5 OR JOYCODE_1_SELECT",
    "P2_BUTTON1": "KEYCODE_A OR MOUSECODE_2_BUTTON1 OR GUNCODE_2_BUTTON1",
    "P2_BUTTON2": "KEYCODE_S OR MOUSECODE_2_BUTTON3 OR GUNCODE_2_BUTTON2",
    "P2_BUTTON3": "KEYCODE_Q OR MOUSECODE_2_BUTTON2",
    "START2": "KEYCODE_2 OR JOYCODE_2_START",
    "COIN2": "KEYCODE_6 OR JOYCODE_2_SELECT",
}
MAME_P1 = {"trigger": "P1_BUTTON1", "a": "P1_BUTTON2", "b": "P1_BUTTON3", "start": "START1", "select": "COIN1"}
MAME_P2 = {"trigger": "P2_BUTTON1", "a": "P2_BUTTON2", "b": "P2_BUTTON3", "start": "START2", "select": "COIN2"}
MAME_NAMES = {"P1_BUTTON1": "BUTTON 1", "P1_BUTTON2": "BUTTON 2", "P1_BUTTON3": "BUTTON 3",
              "START1": "1P START", "COIN1": "COIN 1", "P2_BUTTON1": "P2 BUTTON 1", "P2_BUTTON2": "P2 BUTTON 2",
              "P2_BUTTON3": "P2 BUTTON 3", "START2": "2P START", "COIN2": "COIN 2"}
# what Fix adds when a function has nothing the phone can press (MAME's own default keys)
MAME_ADD = {"P1_BUTTON1": "GUNCODE_1_BUTTON1", "P1_BUTTON2": "KEYCODE_LALT", "P1_BUTTON3": "KEYCODE_SPACE",
            "START1": "KEYCODE_1", "COIN1": "KEYCODE_5", "P2_BUTTON1": "KEYCODE_A", "P2_BUTTON2": "KEYCODE_S",
            "P2_BUTTON3": "KEYCODE_Q", "START2": "KEYCODE_2", "COIN2": "KEYCODE_6"}

_MAME_KEYS = {"LCONTROL": "lctrl", "RCONTROL": "rctrl", "LALT": "lalt", "RALT": "ralt", "LSHIFT": "lshift",
              "RSHIFT": "rshift", "SPACE": "space", "ENTER": "enter", "ESC": "esc", "TAB": "tab",
              "BACKSPACE": "backspace", "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right"}


def _mame_key(name: str) -> str | None:
    if name in _MAME_KEYS:
        return _MAME_KEYS[name]
    if re.fullmatch(r"[A-Z0-9]|F(?:[1-9]|1[0-2])", name):
        return name.lower()
    return None


def mame_code_action(code: str, gun: int, linux: bool, mouse_on: bool) -> str | None:
    """The phone action that produces this MAME input code for gun [gun] (1-based), or None."""
    m = re.fullmatch(r"KEYCODE_(\w+)", code)
    if m:
        k = _mame_key(m.group(1))
        return f"key:{k}" if k else None
    m = re.fullmatch(r"(GUN|MOUSE)CODE_(\d+)_BUTTON(\d)", code)
    if m:
        kind, n, btn = m.group(1), int(m.group(2)), int(m.group(3))
        if kind == "MOUSE" and not mouse_on:
            return None                    # light gun mode: MAME isn't reading the mouse
        if n != gun or (not linux and n != 1):
            return None                    # Windows has one cursor: only gun/mouse 1 is the phone
        return {1: "mouse:left", 2: "mouse:right", 3: "mouse:middle"}.get(btn)
    return None


def mame_usable(seq: str, gun: int, linux: bool, mouse_on: bool) -> str | None:
    """First alternative of a MAME input sequence that the phone can press (gun buttons first)."""
    if not seq or seq.strip() in ("NONE", "DEFAULT"):
        return None
    best = None
    for alt in seq.split(" OR "):
        toks = alt.split()
        if len(toks) != 1:                 # chords and NOT ... can't be sent as one press
            continue
        act = mame_code_action(toks[0], gun, linux, mouse_on)
        if act and act.startswith("mouse:"):
            return act
        best = best or act
    return best


# ---- the installed MAME's own defaults (from its source, matched to its version)
_defaults_lock = threading.Lock()
_defaults: dict[str, dict] = {}        # version tag -> {port type: sequence}
_fetching: set = set()
_versions: dict = {}                   # (exe, mtime) -> tag


def defaults_cache_dir() -> Path:
    from .store import config_dir
    return config_dir() / "mame-defaults"


def parse_inpttype(text: str) -> dict:
    """{port type: sequence string} from MAME's src/emu/inpttype.ipp."""
    out = {}
    for m in re.finditer(r"INPUT_PORT_DIGITAL_TYPE\(\s*\d+\s*,\s*(\w+)\s*,\s*(\w+)\s*,.*?input_seq\((.*)\)\s*\)\s*\\?\s*$",
                         text, re.MULTILINE):
        group, name, body = m.groups()
        ptype = f"P{group[6:]}_{name}" if group.startswith("PLAYER") else name
        alts = []
        for alt in body.split("input_seq::or_code"):
            codes = []
            for c in (x.strip() for x in alt.split(",")):
                if not c:
                    continue
                im = re.fullmatch(r"(\w+?)CODE_(\w+)_INDEXED\((\d+)\)", c)
                codes.append(f"{im.group(1)}CODE_{int(im.group(3)) + 1}_{im.group(2)}" if im else c)
            if codes:
                alts.append(" ".join(codes))
        out[ptype] = " OR ".join(alts) if alts else "NONE"
    return out


def mame_version(exe: str) -> str | None:
    """'mame0289' for MAME 0.289 (asks the program itself, cached per exe)."""
    try:
        key = (exe, Path(exe).stat().st_mtime)
    except OSError:
        return None
    if key in _versions:
        return _versions[key]
    tag = None
    try:
        out = subprocess.run([exe, "-version"], capture_output=True, text=True, timeout=10,
                             creationflags=0x08000000 if sys.platform == "win32" else 0).stdout
        m = re.search(r"\((mame\d+)\)", out) or re.search(r"0\.(\d+)", out)
        if m:
            tag = m.group(1) if m.group(1).startswith("mame") else f"mame0{m.group(1)}"
    except (OSError, subprocess.SubprocessError):
        pass
    _versions[key] = tag
    return tag


def _fetch_defaults(tag: str):
    url = f"https://raw.githubusercontent.com/mamedev/mame/{tag}/src/emu/inpttype.ipp"
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            parsed = parse_inpttype(r.read().decode("utf-8", "replace"))
        if "P1_BUTTON1" not in parsed:
            raise ValueError("unexpected file")
        d = defaults_cache_dir()
        d.mkdir(parents=True, exist_ok=True)
        (d / f"{tag}.json").write_text(json.dumps(parsed, indent=0), encoding="utf-8")
        with _defaults_lock:
            _defaults[tag] = parsed
        log.info("MAME %s: read its default buttons from its source", tag)
    except Exception as e:  # offline, GitHub down, a renamed file in some future version...
        log.info("MAME %s defaults not fetched (%s) - using the built-in table", tag, e)
    finally:
        with _defaults_lock:
            _fetching.discard(tag)


def mame_defaults(exe: str | None, wait: bool = False) -> tuple[dict, str]:
    """({port type: default sequence}, where they came from) for the installed MAME."""
    tag = mame_version(exe) if exe else None
    if tag:
        with _defaults_lock:
            if tag in _defaults:
                return _defaults[tag], f"MAME {tag[4:5]}.{tag[5:]} defaults"
        f = defaults_cache_dir() / f"{tag}.json"
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
            with _defaults_lock:
                _defaults[tag] = d
            return d, f"MAME {tag[4:5]}.{tag[5:]} defaults"
        except (OSError, ValueError):
            pass
        with _defaults_lock:
            start = tag not in _fetching
            _fetching.add(tag)
        if start:
            t = threading.Thread(target=_fetch_defaults, args=(tag,), daemon=True)
            t.start()
            if wait:
                t.join(20)
                return mame_defaults(exe)
    return dict(MAME_BUILTIN), "MAME defaults (built-in copy)"


def mame_cfg_dir(exe: str) -> Path:
    ini = emulocate.mame_ini(exe)
    o = emucheck._read_kv(ini, None) if ini else {}
    d = Path(o.get("cfg_directory", "cfg"))
    return d if d.is_absolute() else Path(exe).parent / d


def mame_cfg_ports(path: Path) -> dict:
    """{port type: sequence} for every port remapped in a MAME cfg file."""
    import xml.etree.ElementTree as ET
    out = {}
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return out
    for port in root.iter("port"):
        seq = port.find("newseq")
        t = port.get("type")
        if seq is not None and t and t not in out:
            out[t] = (seq.text or "").strip() or "NONE"
    return out


def mame_game(title: str | None) -> str | None:
    """Short game name from MAME's window title ('MAME: Area 51 (R3000) [area51]')."""
    m = re.search(r"\[([a-z0-9_]+)\]\s*(?:-.*)?$", title or "")
    return m.group(1) if m else None


def read_mame(exe: str, title: str | None = None) -> Live:
    linux = sys.platform.startswith("linux")
    ini = emulocate.mame_ini(exe)
    o = emucheck._read_kv(ini, None) if ini else {}
    mouse_on = o.get("mouse", "0") == "1"
    defaults, src = mame_defaults(exe)
    cfg_dir = mame_cfg_dir(exe)
    user = mame_cfg_ports(cfg_dir / "default.cfg")
    sources = [src] + (["cfg/default.cfg"] if user else [])
    game = mame_game(title)
    per_game = mame_cfg_ports(cfg_dir / f"{game}.cfg") if game else {}
    if per_game:
        sources.append(f"cfg/{game}.cfg")
    seqs = {**defaults, **user, **per_game}
    live = Live("MAME", "mame", {}, "", {}, sources=sources)

    def player(table: dict, gun: int):
        buttons, labels = {}, {}
        for b, ptype in table.items():
            seq = seqs.get(ptype, MAME_BUILTIN.get(ptype, ""))
            act = mame_usable(seq, gun, linux, mouse_on)
            labels[b] = MAME_NAMES[ptype]
            live.detail[f"{gun}:{b}"] = seq
            if act is None:
                live.unbound.append(ptype)
                labels[b] = MAME_NAMES[ptype] + " (NOT SET)"
            buttons[b] = act or "none"
        buttons["reload"] = _offscreen(buttons["trigger"])
        labels["reload"] = "OFF-SCREEN SHOT"
        return buttons, buttons["reload"], labels
    live.buttons, live.offscreen, live.labels = player(MAME_P1, 1)
    live.p2 = player(MAME_P2, 2)
    return live


# ======================================================================================= RetroArch
RA_FUNCS = {"trigger": "gun_trigger", "reload": "gun_offscreen_shot", "a": "gun_aux_a", "b": "gun_aux_b",
            "start": "gun_start", "select": "gun_select"}
RA_NAMES = {"trigger": "TRIGGER", "reload": "RELOAD", "a": "AUX A", "b": "AUX B", "start": "START",
            "select": "SELECT"}
# RetroArch's built-in light gun defaults (used for keys missing from retroarch.cfg)
RA_DEFAULTS = {"gun_trigger_mbtn": "1", "gun_offscreen_shot_mbtn": "2", "gun_start": "enter", "gun_select": "rshift"}
_RA_KEYS = {"shift": "lshift", "rshift": "rshift", "ctrl": "lctrl", "rctrl": "rctrl", "alt": "lalt",
            "ralt": "ralt", "escape": "esc", "enter": "enter", "space": "space", "tab": "tab",
            "backspace": "backspace", "up": "up", "down": "down", "left": "left", "right": "right"}


def ra_key(name: str) -> str | None:
    n = (name or "").strip().lower()
    if n in _RA_KEYS:
        return _RA_KEYS[n]
    if re.fullmatch(r"[a-z]|f(?:[1-9]|1[0-2])", n):
        return n
    m = re.fullmatch(r"num(\d)", n)
    return m.group(1) if m else None


def ra_gun_port(rp, cfg: dict) -> int:
    content, core_path = emucheck._ra_last_content(rp.playlists)
    cur = {k: v for k, v in cfg.items() if k.startswith("input_libretro_device_p")}
    if content and core_path:
        core = emucheck._ra_core_name(rp.info, core_path)
        game = Path(content.replace("\\", "/")).stem
        cur.update(emucheck._read_kv(rp.remaps / core / f"{core}.rmp", "="))
        cur.update(emucheck._read_kv(rp.remaps / core / f"{game}.rmp", "="))
    for n in range(1, 9):
        try:
            if int(cur.get(f"input_libretro_device_p{n}", "1")) & 0xFF == 4:
                return n
        except ValueError:
            pass
    return 1


def ra_bind(cfg: dict, port: int, func: str) -> tuple[str | None, str]:
    """(phone action, binding text) for RetroArch's input_player<port>_<func>."""
    def get(suffix):
        k = f"input_player{port}_{func}{suffix}"
        return cfg[k] if k in cfg else RA_DEFAULTS.get(func + suffix, "nul")
    mb, key = get("_mbtn"), get("")
    act = {"1": "mouse:left", "2": "mouse:right", "3": "mouse:middle"}.get(mb)
    k = ra_key(key)
    text = " or ".join(x for x in ((f"mouse {mb}" if mb not in ("nul", "") else ""),
                                   (key if key not in ("nul", "") else "")) if x) or "not set"
    return act or (f"key:{k}" if k else None), text


def ra_effective_cfg(rp, cfg: dict | None = None) -> dict:
    """retroarch.cfg with the loaded core's and game's override files on top, as RetroArch layers them."""
    cfg = dict(cfg if cfg is not None else emucheck._read_kv(rp.cfg, "="))
    content, core_path = emucheck._ra_last_content(rp.playlists)
    if core_path:
        core = emucheck._ra_core_name(rp.info, core_path)
        cfg.update(emucheck._read_kv(rp.overrides / core / f"{core}.cfg", "="))
        if content:
            game = Path(content.replace("\\", "/")).stem
            cfg.update(emucheck._read_kv(rp.overrides / core / f"{game}.cfg", "="))
    return cfg


def ra_core(rp) -> str:
    content, core_path = emucheck._ra_last_content(rp.playlists)
    return emucheck._ra_core_name(rp.info, core_path) if core_path else ""


def is_mame_core(core: str) -> bool:
    """The current MAME core: no light gun device type, and it reads the RetroPad as well as the gun."""
    return core.strip().upper() in ("MAME", "HBMAME")


_RA_NOT_BINDS = ("_btn", "_axis", "_mbtn", "_index", "_delay", "_scale", "_device", "_driver", "_mode", "_type")


def ra_keyboard_binds(cfg: dict) -> dict:
    """Every keyboard binding in retroarch.cfg (players and hotkeys): {setting: key}."""
    return {k: v for k, v in cfg.items()
            if k.startswith("input_") and not k.endswith(_RA_NOT_BINDS) and ra_key(v) is not None}


def ra_carriers(port: int, mame_core: bool) -> dict:
    """Phone button -> the RetroArch setting whose key the phone presses for it. The MAME core takes
    Start/Coin from the RetroPad (Start / Select), the gun cores from the gun's own Start/Select."""
    out = {b: f"input_player{port}_{f}" for b, f in RA_FUNCS.items()}
    if mame_core:
        out.update({"start": f"input_player{port}_start", "select": f"input_player{port}_select"})
    return out


def ra_conflicts(cfg: dict, port: int, mame_core: bool) -> list:
    """[(phone button, its setting, other setting with the same key)]: one press = two things.
    Tested: Right Shift on both player 1 Select and player 2 Gun Select gave 2 coins per press."""
    kb = ra_keyboard_binds(cfg)
    out = []
    for b, carrier in ra_carriers(port, mame_core).items():
        if cfg.get(carrier + "_mbtn", "nul") in ("1", "2", "3"):
            continue                           # the phone clicks the mouse for this one: its key isn't used
        key = kb.get(carrier)
        if key is None:
            continue
        for other, v in kb.items():
            if v != key or other == carrier:
                continue
            if other.startswith(f"input_player{port}_") and not mame_core and "_gun_" not in other:
                continue                       # a gun port ignores its RetroPad bindings
            out.append((b, carrier, other))
    return out


def read_retroarch(exe: str) -> Live | None:
    rp = emulocate.retroarch_paths(exe, emucheck._read_kv)
    if rp is None:
        return None
    cfg = ra_effective_cfg(rp)
    port = ra_gun_port(rp, cfg)
    mame = is_mame_core(ra_core(rp))
    live = Live("RetroArch", "retroarch", {}, "", {},
                sources=[f"{rp.cfg.name} (port {port}" + (", MAME core" if mame else "") + ")"])
    for b, func in RA_FUNCS.items():
        if mame and b in ("start", "select"):
            key = cfg.get(f"input_player{port}_{b}", "enter" if b == "start" else "rshift")
            k = ra_key(key)
            act, text = (f"key:{k}" if k else None), key
        else:
            act, text = ra_bind(cfg, port, func)
        live.detail[b] = text
        name = {"start": "1P START", "select": "COIN"}.get(b, RA_NAMES[b]) if mame else RA_NAMES[b]
        live.labels[b] = name if act else name + " (NOT SET)"
        if act is None:
            live.unbound.append(func)
        live.buttons[b] = act or "none"
    live.offscreen = live.buttons["reload"]
    live.conflicts = [f"{cfg.get(c)} is also {o[6:]}" for _, c, o in ra_conflicts(cfg, port, mame)]
    return live


# ============================================================================ DuckStation / PCSX2
def read_ini_multi(path: Path) -> dict:
    """{'Section/Key': [values]} - these emulators list one key several times for alternatives."""
    out: dict = {}
    sec = ""
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return out
    for line in lines:
        s = line.strip()
        if s.startswith("[") and s.endswith("]"):
            sec = s[1:-1].strip()
        elif "=" in s and not s.startswith((";", "#")):
            k, v = s.split("=", 1)
            out.setdefault(f"{sec}/{k.strip()}", []).append(v.strip().strip('"'))
    return out


_QT_KEYS = {"return": "enter", "enter": "enter", "escape": "esc", "space": "space", "tab": "tab",
            "backspace": "backspace", "shift": "lshift", "control": "lctrl", "alt": "lalt", "up": "up",
            "down": "down", "left": "left", "right": "right"}


def qt_bind_action(v: str) -> str | None:
    """'Pointer-0/LeftButton' / 'Keyboard/R' (DuckStation, PCSX2) -> phone action."""
    v = v.strip()
    if "&" in v:
        return None
    m = re.fullmatch(r"Pointer-0/(Left|Right|Middle)Button", v)
    if m:
        return "mouse:" + m.group(1).lower()
    m = re.fullmatch(r"Keyboard/(\w+)", v)
    if m:
        n = m.group(1).lower()
        if n in _QT_KEYS:
            return "key:" + _QT_KEYS[n]
        if re.fullmatch(r"[a-z0-9]|f(?:[1-9]|1[0-2])", n):
            return "key:" + n
    return None


def _first_usable(values: list) -> str | None:
    acts = [a for a in (qt_bind_action(v) for v in values) if a]
    return next((a for a in acts if a.startswith("mouse:")), acts[0] if acts else None)


DS_GUN_TYPES = ("GunCon", "Justifier")
DS_FUNCS = {"trigger": "Trigger", "reload": "ShootOffscreen", "a": "A", "b": "B", "start": "Start", "select": "Back"}
PCSX2_FUNCS = {"trigger": "Trigger", "reload": "ShootOffscreen", "a": "A", "b": "B", "start": "Start",
               "select": "Select"}


def ds_gun_pad(have: dict) -> str | None:
    for n in range(1, 9):
        if (have.get(f"Pad{n}/Type") or [""])[-1] in DS_GUN_TYPES:
            return f"Pad{n}"
    return None


def pcsx2_gun_port(have: dict) -> str | None:
    for n in (1, 2):
        if (have.get(f"USB{n}/Type") or [""])[-1] == "guncon2":
            return f"USB{n}"
    return None


def _read_qt(name: str, preset: str, ini: Path, prefix: str | None, funcs: dict, keyfmt) -> Live:
    have = read_ini_multi(ini)
    live = Live(name, preset, {}, "", {}, sources=[ini.name + (f" ({prefix})" if prefix else "")])
    for b, f in funcs.items():
        vals = have.get(keyfmt(prefix, f), []) if prefix else []
        act = _first_usable(vals)
        live.detail[b] = " or ".join(vals) or "not set"
        nice = {"trigger": "TRIGGER", "reload": "OFF-SCREEN", "a": "A", "b": "B"}.get(b, f.upper())
        live.labels[b] = nice if act else nice + " (NOT SET)"
        if act is None and (b in ("trigger", "reload", "a", "b") or vals):
            live.unbound.append(f)
        live.buttons[b] = act or "none"
    live.offscreen = live.buttons["reload"]
    return live


def read_duckstation(exe: str) -> Live:
    from .emufix import duckstation_ini
    ini = duckstation_ini(exe)
    pad = ds_gun_pad(read_ini_multi(ini))
    live = _read_qt("DuckStation", "duckstation", ini, pad, DS_FUNCS, lambda p, f: f"{p}/{f}")
    if pad is None:
        live.unbound.insert(0, "no GunCon port")
    return live


def read_pcsx2(exe: str) -> Live:
    from .emufix import pcsx2_ini
    ini = pcsx2_ini(exe)
    port = pcsx2_gun_port(read_ini_multi(ini))
    live = _read_qt("PCSX2", "pcsx2", ini, port, PCSX2_FUNCS, lambda p, f: f"{p}/guncon2_{f}")
    if port is None:
        live.unbound.insert(0, "no GunCon 2 on a USB port")
    return live


# ========================================================================================= Dolphin
DOLPHIN_FUNCS = {"trigger": ("Buttons/B", "WII B"), "reload": ("Shake/X", "SHAKE"), "a": ("Buttons/A", "WII A"),
                 "b": ("Buttons/1", "WII 1"), "start": ("Buttons/+", "WII +"), "select": ("Buttons/-", "WII -")}
DOLPHIN_DEFAULTS = {"Buttons/A": "`Click 0`", "Buttons/B": "`Click 1`", "Buttons/1": "`1`", "Buttons/+": "`E`",
                    "Buttons/-": "`Q`", "Shake/X": "`Click 2`"}
_DOLPHIN_KEYS = {"return": "enter", "space": "space", "escape": "esc", "tab": "tab", "back": "backspace",
                 "backspace": "backspace", "lcontrol": "lctrl", "control_l": "lctrl", "rcontrol": "rctrl",
                 "control_r": "rctrl", "lshift": "lshift", "shift_l": "lshift", "rshift": "rshift",
                 "shift_r": "rshift", "lmenu": "lalt", "alt_l": "lalt", "rmenu": "ralt", "alt_r": "ralt",
                 "up": "up", "down": "down", "left": "left", "right": "right"}


def dolphin_action(expr: str) -> str | None:
    acts = []
    for alt in (expr or "").split("|"):
        n = alt.strip().strip("`").strip()
        if not n or "&" in n or "!" in n:
            continue
        m = re.fullmatch(r"Click (\d)", n)
        if m:
            acts.append({"0": "mouse:left", "1": "mouse:right", "2": "mouse:middle"}.get(m.group(1)))
            continue
        low = n.lower()
        if low in _DOLPHIN_KEYS:
            acts.append("key:" + _DOLPHIN_KEYS[low])
        elif re.fullmatch(r"[a-z0-9]|f(?:[1-9]|1[0-2])", low):
            acts.append("key:" + low)
    acts = [a for a in acts if a]
    return next((a for a in acts if a.startswith("mouse:")), acts[0] if acts else None)


def dolphin_ini(exe: str) -> Path:
    from .emufix import _documents
    import os
    d = Path(exe).parent
    if (d / "portable.txt").exists():
        return d / "User" / "Config" / "WiimoteNew.ini"
    if sys.platform == "win32":
        return _documents() / "Dolphin Emulator" / "Config" / "WiimoteNew.ini"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "Dolphin" / "Config" / "WiimoteNew.ini"
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "dolphin-emu" / "WiimoteNew.ini"


def read_dolphin(exe: str) -> Live:
    ini = dolphin_ini(exe)
    have = {k.split("/", 1)[1]: v[-1] for k, v in read_ini_multi(ini).items() if k.startswith("Wiimote1/")}
    live = Live("Dolphin", "dolphin", {}, "", {},
                sources=[f"{ini.name} (Wii Remote 1)" if have else "Dolphin defaults (no WiimoteNew.ini yet)"])
    for b, (key, nice) in DOLPHIN_FUNCS.items():
        expr = have.get(key, DOLPHIN_DEFAULTS[key]) if have else DOLPHIN_DEFAULTS[key]
        act = dolphin_action(expr)
        live.detail[b] = expr or "not set"
        live.labels[b] = nice if act else nice + " (NOT SET)"
        if act is None:
            live.unbound.append(key)
        live.buttons[b] = act or "none"
    live.offscreen = live.buttons["reload"]
    return live


# ============================================================================================ entry
# ====================================================================================== Supermodel
# Supermodel (Sega Model 3): Config/Supermodel.ini, keys "Input<Id>" (Src/Inputs/Input.cpp), values like
# "KEY_A,JOY1_BUTTON1,MOUSE_LEFT_BUTTON" (',' = or, '+' = together). Current builds have NO default
# bindings in the program (Src/Inputs/Inputs.cpp: "NONE"), only what the shipped .ini lists.
SM_FUNCS = {"trigger": ("Trigger", "TRIGGER"), "reload": ("Offscreen", "OFF-SCREEN"),
            "start": ("Start1", "1P START"), "select": ("Coin1", "COIN 1")}
# what Fix adds where nothing usable is bound (the mouse buttons/axes, and the shipped keys)
SM_ADD = {"Trigger": "MOUSE_LEFT_BUTTON", "Offscreen": "MOUSE_RIGHT_BUTTON", "Start1": "KEY_1", "Coin1": "KEY_3",
          "GunX": "MOUSE_XAXIS", "GunY": "MOUSE_YAXIS", "AnalogGunX": "MOUSE_XAXIS", "AnalogGunY": "MOUSE_YAXIS",
          "AnalogTriggerLeft": "MOUSE_LEFT_BUTTON", "AnalogTriggerRight": "MOUSE_RIGHT_BUTTON"}
_SM_KEYS = {"RETURN": "enter", "SPACE": "space", "ESCAPE": "esc", "TAB": "tab", "BACKSPACE": "backspace",
            "LEFTCTRL": "lctrl", "CTRL": "lctrl", "RIGHTCTRL": "rctrl", "LEFTALT": "lalt", "ALT": "lalt",
            "RIGHTALT": "ralt", "LEFTSHIFT": "lshift", "SHIFT": "lshift", "RIGHTSHIFT": "rshift",
            "UP": "up", "DOWN": "down", "LEFT": "left", "RIGHT": "right"}


def sm_token_action(tok: str) -> str | None:
    tok = tok.strip()
    m = re.fullmatch(r"MOUSE1?_(LEFT|RIGHT|MIDDLE)_BUTTON", tok)
    if m:
        return "mouse:" + m.group(1).lower()
    m = re.fullmatch(r"KEY_(\w+)", tok)
    if m:
        n = m.group(1)
        if n in _SM_KEYS:
            return "key:" + _SM_KEYS[n]
        if re.fullmatch(r"[A-Z0-9]|F(?:[1-9]|1[0-2])", n):
            return "key:" + n.lower()
    return None


def sm_usable(value: str) -> str | None:
    acts = [a for a in (sm_token_action(t) for t in (value or "").split(",") if "+" not in t) if a]
    return next((a for a in acts if a.startswith("mouse:")), acts[0] if acts else None)


def sm_has_axis(value: str, axis: str) -> bool:
    return any(t.strip() in (f"MOUSE_{axis}AXIS", f"MOUSE1_{axis}AXIS") for t in (value or "").split(","))


def supermodel_ini(exe: str) -> Path:
    import os
    d = Path(exe).parent
    cands = [d / "Config" / "Supermodel.ini"]
    if not sys.platform.startswith("win"):
        xdg = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
        cands += [xdg / "supermodel" / "Config" / "Supermodel.ini", Path.home() / ".supermodel" / "Config" / "Supermodel.ini"]
    return next((c for c in cands if c.exists()), cands[0])


def is_supermodel(exe: str | None) -> bool:
    return bool(exe) and Path(exe).name.lower().startswith("supermodel")


def read_supermodel(exe: str) -> Live | None:
    if not is_supermodel(exe):
        return None                         # the Sega Model 2 emulator shares this preset: not readable
    ini = supermodel_ini(exe)
    have = {k.split("/", 1)[1]: v[-1] for k, v in read_ini_multi(ini).items() if k.startswith("Global/")}
    live = Live("Supermodel", "model2_supermodel", {}, "", {}, sources=[f"{ini.parent.name}/{ini.name}"])
    for b, (key, nice) in SM_FUNCS.items():
        val = have.get("Input" + key, "NONE")
        act = sm_usable(val)
        live.detail[b] = val
        live.labels[b] = nice if act else nice + " (NOT SET)"
        if act is None:
            live.unbound.append(key)
        live.buttons[b] = act or "none"
    for axis in ("X", "Y"):
        if not sm_has_axis(have.get(f"InputGun{axis}", ""), axis):
            live.unbound.append(f"Gun{axis} (aim)")
    live.buttons.update({"a": "none", "b": "none"})
    live.labels.update({"a": "-", "b": "-"})
    live.offscreen = live.buttons["reload"]
    return live


# ========================================================================================= Flycast
# Flycast (Dreamcast / Naomi / Atomiswave): mapping files in <config>/mappings, named
# "<api>_<device>[_arcade].cfg", "[digital] bindN = <code>:<function>" (core/input/mapping.cpp).
# Built-in defaults (core/input/mouse.h, keyboard_device.h): mouse left = A (trigger), right = B,
# middle = Start; keyboard Return = Start, A = Coin (btn_d), X = A, C = B, Space = fast-forward.
FC_MOUSE_DEFAULT = {"btn_a": ["2"], "btn_b": ["1"], "btn_start": ["3"]}
FC_KEYS_DEFAULT = {"btn_a": ["27"], "btn_b": ["6"], "btn_start": ["40"], "btn_d": ["4"]}
FC_FUNCS = {"trigger": ("btn_a", "TRIGGER (A)"), "reload": ("reload", "RELOAD"), "a": ("btn_b", "B"),
            "b": ("btn_c", "C"), "start": ("btn_start", "START"), "select": ("btn_d", "COIN")}
# SDL scancodes -> key names
_SDL = {**{4 + i: chr(97 + i) for i in range(26)}, **{30 + i: str((i + 1) % 10) for i in range(10)},
        40: "enter", 41: "esc", 42: "backspace", 43: "tab", 44: "space", **{58 + i: f"f{i + 1}" for i in range(12)},
        79: "right", 80: "left", 81: "down", 82: "up", 224: "lctrl", 225: "lshift", 226: "lalt", 228: "rctrl",
        229: "rshift", 230: "ralt"}


def flycast_dir(exe: str) -> Path:
    import os
    d = Path(exe).parent
    if (d / "emu.cfg").exists():
        return d
    if sys.platform == "win32":
        return Path(os.environ.get("APPDATA", Path.home())) / "flycast"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "Flycast"
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "flycast"


def _fc_mapping(path: Path | None) -> dict | None:
    """{function: [codes]} from a Flycast mapping file (port 0 only), or None if there's no file."""
    if path is None or not path.exists():
        return None
    out: dict = {}
    for k, vals in read_ini_multi(path).items():
        if not k.startswith("digital/bind"):
            continue
        for v in vals:
            code, _, func = v.partition(":")
            if func and code.strip().lstrip("-").isdigit():
                out.setdefault(func.strip(), []).append(code.strip())
    return out


def read_flycast(exe: str) -> Live:
    maps = flycast_dir(exe) / "mappings"
    try:
        files = sorted(maps.glob("*.cfg"))
    except OSError:
        files = []

    def pick(word):
        # arcade mapping first (gun games are mostly Naomi/Atomiswave), then the per-device file Flycast
        # prefers ("SDL_Keyboard-sdl_keyboard"), shortest name first (longer ones are per-game)
        cands = sorted((f for f in files if word in f.stem.lower()),
                       key=lambda f: (not f.stem.endswith("_arcade"), "-" not in f.stem, len(f.stem)))
        return cands[0] if cands else None
    mf, kf = pick("mouse"), pick("keyboard")
    mouse, keys = _fc_mapping(mf), _fc_mapping(kf)
    live = Live("Flycast", "flycast", {}, "", {},
                sources=[f.name for f in (mf, kf) if f is not None and f.exists()] or ["Flycast defaults"])
    mouse = FC_MOUSE_DEFAULT if mouse is None else mouse
    keys = FC_KEYS_DEFAULT if keys is None else keys
    for b, (func, nice) in FC_FUNCS.items():
        act = None
        for c in mouse.get(func, []):
            act = act or {"2": "mouse:left", "1": "mouse:right", "3": "mouse:middle"}.get(c)
        for c in keys.get(func, []):
            k = _SDL.get(int(c)) if c.isdigit() else None
            act = act or (f"key:{k}" if k else None)
        live.detail[b] = f"mouse {mouse.get(func, [])} keys {keys.get(func, [])}"
        if b == "reload" and act is None:
            act = "offscreen:left"          # Flycast reloads when the gun points off the screen
            nice = "OFF-SCREEN SHOT"
        live.labels[b] = nice if act else ("-" if b == "b" else nice + " (NOT SET)")
        if act is None and b in ("trigger", "start", "select"):
            live.unbound.append(func)
        live.buttons[b] = act or "none"
    live.offscreen = live.buttons["reload"] if live.buttons["reload"].startswith("offscreen") \
        else _offscreen(live.buttons["trigger"])
    return live


# ========================================================================================= Mesen 2
# Mesen 2 (NES / SNES / Master System): settings.json next to the exe (portable) or Documents\Mesen2
# (~/.config/Mesen2). Each port has "Type" and Mapping1..4 with per-device arrays of key codes
# (UI/Config/*ControllerConfig.cs). Mouse buttons are 0x200 + n (Interfaces/IKeyManager.h); 0 = unset.
# Defaults: Zapper / Light Phaser [Fire, AimOffscreen] = [Mouse Left, Mouse Right];
# Super Scope [Fire, Cursor, Turbo, Pause] = [Mouse Left, Mouse Right, Mouse Middle, Enter].
MESEN_GUNS = {  # type -> (system, mapping array, [(phone button, index, name)], defaults)
    "NesZapper": ("NES Zapper", "ZapperButtons", [("trigger", 0, "FIRE"), ("reload", 1, "AIM OFF-SCREEN")], [512, 513]),
    "FamicomZapper": ("Famicom Zapper", "ZapperButtons", [("trigger", 0, "FIRE"), ("reload", 1, "AIM OFF-SCREEN")], [512, 513]),
    "SuperScope": ("Super Scope", "SuperScopeButtons",
                   [("trigger", 0, "FIRE"), ("a", 1, "CURSOR"), ("b", 2, "TURBO"), ("start", 3, "PAUSE")], [512, 513, 514, 0]),
    "SmsLightPhaser": ("Light Phaser", "LightPhaserButtons", [("trigger", 0, "FIRE"), ("reload", 1, "AIM OFF-SCREEN")], [512, 513]),
}
MESEN_TYPE_NUM = {9: "NesZapper", 3: "SuperScope"}          # core enum order, if saved as a number
_MESEN_MOUSE = {512: "mouse:left", 513: "mouse:right", 514: "mouse:middle"}


def mesen_settings(exe: str) -> Path:
    import os
    d = Path(exe).parent
    if (d / "settings.json").exists():
        return d / "settings.json"
    if sys.platform == "win32":
        import ctypes.wintypes
        buf = ctypes.create_unicode_buffer(260)
        ctypes.windll.shell32.SHGetFolderPathW(None, 5, None, 0, buf)   # My Documents (may be redirected)
        docs = Path(buf.value or Path.home() / "Documents")
        return docs / "Mesen2" / "settings.json"
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "Mesen2" / "settings.json"


def _mesen_guns(cfg: dict) -> list:
    out = []
    for system, ports in (("Nes", ("Port1", "Port2", "ExpPort")), ("Snes", ("Port1", "Port2")), ("Sms", ("Port1", "Port2"))):
        for port in ports:
            pc = (cfg.get(system) or {}).get(port) or {}
            t = pc.get("Type")
            t = MESEN_TYPE_NUM.get(t, t) if isinstance(t, int) else t
            if t in MESEN_GUNS:
                out.append((system, port, t, pc))
    return out


def read_mesen(exe: str) -> Live | None:
    path = mesen_settings(exe)
    try:
        cfg = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        cfg = {}
    guns = _mesen_guns(cfg)
    live = Live("Mesen", "mesen", {b: "none" for b in PHONE}, "", {b: "-" for b in PHONE},
                sources=[f"{path.parent.name}/{path.name}"])
    if not guns:
        live.unbound.append("light gun port (set a port to Zapper / Super Scope / Light Phaser)")
        live.buttons.update({"trigger": "mouse:left", "reload": "offscreen:left"})
        live.offscreen = "offscreen:left"
        return live
    system, port, t, pc = guns[0]
    nice, arr, funcs, defaults = MESEN_GUNS[t]
    codes = None
    for m in ("Mapping1", "Mapping2", "Mapping3", "Mapping4"):
        a = (pc.get(m) or {}).get(arr)
        if a:
            codes = codes or [0] * len(defaults)
            for i, c in enumerate(a[:len(codes)]):
                codes[i] = codes[i] or (c or 0)      # first mapping that sets it wins
    codes = codes or defaults
    live.sources[0] += f" ({system} {port}: {nice})"
    for b, i, name in funcs:
        c = codes[i] if i < len(codes) else 0
        act = _MESEN_MOUSE.get(c)
        live.detail[b] = str(c)
        if act is None and b == "reload":
            act, name = "offscreen:left", "OFF-SCREEN SHOT"
        live.labels[b] = name if act else (name + (" (NOT SET)" if not c else " (KEYBOARD KEY)"))
        if act is None and b == "trigger":
            live.unbound.append(name)
        live.buttons[b] = act or "none"
    live.offscreen = live.buttons["reload"] if live.buttons["reload"].startswith("offscreen") \
        else _offscreen(live.buttons["trigger"])
    return live


READERS = {"retroarch": lambda exe, title: read_retroarch(exe), "mame": read_mame,
           "duckstation": lambda exe, title: read_duckstation(exe), "pcsx2": lambda exe, title: read_pcsx2(exe),
           "dolphin": lambda exe, title: read_dolphin(exe),
           "model2_supermodel": lambda exe, title: read_supermodel(exe),
           "flycast": lambda exe, title: read_flycast(exe),
           "mesen": lambda exe, title: read_mesen(exe)}

_cache: dict = {}


def read(preset: str | None, exe: str | None, title: str | None = None, max_age: float = 2.0) -> Live | None:
    """The emulator's current bindings, or None if this emulator's files can't be read."""
    fn = READERS.get(preset or "")
    if fn is None or not exe:
        return None
    k = (preset, exe, mame_game(title) if preset == "mame" else None)
    hit = _cache.get(k)
    if hit and time.monotonic() - hit[0] < max_age:
        return hit[1]
    try:
        live = fn(exe, title)
    except Exception as e:  # a half-written file while the emulator saves, an odd format...
        log.warning("couldn't read %s button settings: %s", preset, e)
        live = hit[1] if hit else None
    _cache[k] = (time.monotonic(), live)
    return live
