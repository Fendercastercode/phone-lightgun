"""Fix an emulator's settings so it reads the phone gun.

Before anything is saved, every file about to change is copied into a dated backup folder
(RetroSight's settings folder / backups / <emulator> / <date-time>), and a file that didn't exist
yet is noted as new. "Undo" puts the last backup back. Bindings you already have are kept: the
fix only adds a binding where a function has nothing the phone can press.
Files the emulator rewrites when it exits (RetroArch's retroarch.cfg and core options) are
only written while it is closed; those changes wait until it exits. Per-game remap files are
safe to write at any time and take effect the next time the game is loaded.
"""
from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import bindread, emucheck, emulocate
from .autopreset import preset_for

log = logging.getLogger(__name__)

# Console light gun games (for RetroArch, where the gun is set per game). Automatic setup only
# touches a RetroArch game whose name matches one of these, so normal games are never changed.
GUN_GAMES = (
    # NES Zapper
    "duck hunt", "hogan's alley", "hogans alley", "wild gunman", "gumshoe", "bayou billy", "baby boomer",
    "barker bill", "chiller", "freedom force", "laser invasion", "mechanized attack", "operation wolf",
    "shooting range", "to the earth", "space shadow", "crime busters", "master shooter", "lone ranger",
    # SNES Super Scope / Justifier
    "super scope", "battle clash", "metal combat", "terminator 2 - the arcade game", "t2 - the arcade game",
    "t2 the arcade game", "x-zone", "x zone", "yoshi's safari", "yoshis safari", "tin star", "lethal enforcers",
    "operation thunderbolt", "bazooka blitzkrieg", "hunt for red october",
    # Mega Drive / Genesis / Sega CD (Menacer, Justifier) and Master System (Light Phaser)
    "menacer", "body count", "snatcher", "corpse killer", "crime patrol", "who shot johnny rock", "mad dog",
    "space pirates", "drug wars", "last bounty hunter", "safari hunt", "marksman shooting", "trap shooting",
    "shooting gallery", "rambo iii", "rescue mission", "gangster town", "assault city", "missile defense",
    "space gun", "laser ghost",
    # PlayStation GunCon / Justifier
    "time crisis", "point blank", "gunbullet", "die hard trilogy", "elemental gearbolt", "ghoul panic",
    "gunfighter", "judge dredd", "maximum force", "area 51", "crypt killer", "rescue shot", "resident evil survivor",
    "biohazard gun survivor", "gun survivor", "extreme ghostbusters", "project horned owl", "moorhuhn", "mighty hits",
    "simple 1500 series vol. 24 - the gun shooting", "the gun shooting", "guncon", "policenauts",
    # Saturn Stunner / Virtua Gun
    "virtua cop", "house of the dead", "death crimson", "mechanical violator hakaider", "scud the disposable",
    "chaos control", "mighty hits",
    # Dreamcast / Naomi / Atomiswave (Flycast core) and 3DO
    "confidential mission", "ninja assault", "maze of the kings", "lupin the third", "rabbit punch",
    "sports shooting usa", "extreme hunting", "kick'n'kill",
)


def looks_like_gun_game(name: str) -> bool:
    n = re.sub(r"[\(\[].*?[\)\]]", " ", (name or "").lower())     # drop "(USA)", "[!]"...
    n = re.sub(r"\s+", " ", n).strip()
    return any(g in n for g in GUN_GAMES)


_mame_gun_cache: dict = {}


def mame_says_gun(short_name: str, mame_exe: str | None) -> bool | None:
    """Arcade games have short names ('lethalen'): ask the standalone MAME whether the game has a
    light gun (mame -listxml). None when MAME isn't available or doesn't know the game."""
    if not mame_exe or not re.fullmatch(r"[a-z0-9_]+", short_name or ""):
        return None
    if short_name in _mame_gun_cache:
        return _mame_gun_cache[short_name]
    ans = None
    try:
        out = subprocess.run([mame_exe, "-listxml", short_name], capture_output=True, text=True, timeout=20,
                             creationflags=0x08000000 if sys.platform == "win32" else 0).stdout
        if "<machine" in out:
            ans = 'type="lightgun"' in out
    except (OSError, subprocess.SubprocessError):
        pass
    _mame_gun_cache[short_name] = ans
    return ans


KONAMI_GUN_GAMES = ("lethal enforcers", "snatcher", "crypt killer", "policenauts")
GUN_PREFERENCE = ("menacer", "guncon", "g-con", "super scope", "superscope", "zapper", "light gun",
                  "lightgun", "phaser", "virtua gun", "stunner", "gun", "justifier")
# Some cores (Mesen's Zapper is RETRO_DEVICE_POINTER, FCEUmm's is RETRO_DEVICE_MOUSE) don't file
# their gun under RETRO_DEVICE_LIGHTGUN at all, so a device class check alone misses them. Matched
# by name instead; narrower than GUN_PREFERENCE (no bare "gun") to avoid false positives.
GUN_DEVICE_NAMES = ("menacer", "guncon", "g-con", "super scope", "superscope", "zapper", "light gun",
                     "lightgun", "virtua gun", "stunner", "justifier", "light phaser", "phaser")
JOYPAD = 1


def looks_like_gun_device(desc: str) -> bool:
    d = desc.lower()
    return any(h in d for h in GUN_DEVICE_NAMES)


@dataclass
class Edit:
    path: Path
    values: dict              # key -> value
    style: str                # "cfg" (key = "value") or "ini" (key   value)
    when: str = "now"         # "now" or "closed" (emulator must not be running)
    why: str = ""


@dataclass
class FixPlan:
    emulator: str
    exe: str
    edits: list[Edit] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    create_mame_ini: bool = False

    def describe(self) -> list[str]:
        out = []
        for e in self.edits:
            vals = ", ".join(f"{k} = {v}" for k, v in e.values.items())
            out.append(f"{e.path.name}: {vals}" + ("  (when it closes)" if e.when == "closed" else "")
                       + (f"  - {e.why}" if e.why else ""))
        return out + self.notes


# ---------------------------------------------------------------------------------- backups
BACKUP_ROOT: Path | None = None     # tests point this somewhere else
BACKUP_KEEP = 20                    # backups kept per emulator


def backup_root() -> Path:
    if BACKUP_ROOT is not None:
        return BACKUP_ROOT
    from .store import config_dir
    return config_dir() / "backups"


class Backup:
    """One dated backup folder per save: a copy of each file before it changes, plus a manifest."""

    def __init__(self, emulator: str):
        self.emulator = emulator
        stamp = time.strftime("%Y%m%d-%H%M%S")
        base = backup_root() / re.sub(r"[^\w.-]", "_", emulator)
        d = base / stamp
        n = 1
        while d.exists():
            n += 1
            d = base / f"{stamp}-{n}"
        self.dir = d
        self.files: list[dict] = []

    def save(self, p: Path):
        """Call before writing [p]. Copies it (or records that it's new). Once per file."""
        p = Path(p)
        if any(f["path"] == str(p) for f in self.files):
            return
        self.dir.mkdir(parents=True, exist_ok=True)
        entry = {"path": str(p), "copy": None}
        if p.exists():
            name = f"{len(self.files) + 1:02d}-{p.name}"
            shutil.copy2(p, self.dir / name)
            entry["copy"] = name
        self.files.append(entry)
        (self.dir / "manifest.json").write_text(json.dumps(
            {"emulator": self.emulator, "time": time.strftime("%Y-%m-%d %H:%M:%S"), "files": self.files},
            indent=1), encoding="utf-8")
        _prune(self.dir.parent)


def _prune(base: Path):
    sets = sorted((d for d in base.iterdir() if (d / "manifest.json").exists()), key=lambda d: d.name)
    for old in sets[:-BACKUP_KEEP]:
        shutil.rmtree(old, ignore_errors=True)


def backups(emulator: str) -> list[Path]:
    """Backup folders for this emulator, newest first."""
    base = backup_root() / re.sub(r"[^\w.-]", "_", emulator)
    try:
        return sorted((d for d in base.iterdir() if (d / "manifest.json").exists()), key=lambda d: d.name,
                      reverse=True)
    except OSError:
        return []


def restore(backup_dir: Path) -> list[str]:
    """Put every file in a backup back the way it was (files that were new are removed).
    The current files are backed up first, so a restore can be undone too."""
    m = json.loads((backup_dir / "manifest.json").read_text(encoding="utf-8"))
    undo = Backup(m.get("emulator", "unknown"))
    done = []
    for f in m["files"]:
        p = Path(f["path"])
        undo.save(p)
        if f["copy"] is None:
            if p.exists():
                p.unlink()
        else:
            p.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup_dir / f["copy"], p)
        done.append(p.name)
    shutil.rmtree(backup_dir, ignore_errors=True)      # used up (its "undo" copy is the new newest)
    log.info("restored %s from %s", ", ".join(done), backup_dir)
    return done


# Player 2 aims with the virtual Xbox pad's right stick (see presets.P2_BUTTONS for its buttons)
MAME_P2_INPUT = {"P2_LIGHTGUN_X": "JOYCODE_1_RXAXIS", "P2_LIGHTGUN_Y": "JOYCODE_1_RYAXIS"}

# MAME's default buttons (also read from the installed version's source: see bindread)
MAME_BUTTONS = dict(bindread.MAME_BUILTIN)
# a per-game remap (cfg/<rom>.cfg) of any of these overrides the defaults for that game
MAME_GUN_PORTS = set(MAME_BUTTONS) | {"P1_LIGHTGUN_X", "P1_LIGHTGUN_Y", "P2_LIGHTGUN_X", "P2_LIGHTGUN_Y"}


def mame_game_remaps(cfg_dir: Path) -> dict[Path, list[str]]:
    """cfg/<rom>.cfg files that remap the gun, buttons, start or coin."""
    import xml.etree.ElementTree as ET
    out = {}
    try:
        files = [f for f in cfg_dir.glob("*.cfg") if f.name.lower() != "default.cfg"]
    except OSError:
        return out
    for f in files:
        try:
            if "newseq" not in f.read_text(encoding="utf-8", errors="replace"):
                continue
            root = ET.parse(f).getroot()
        except Exception:
            continue
        types = sorted({p.get("type") for p in root.iter("port")
                        if p.get("type") in MAME_GUN_PORTS and p.find("newseq") is not None})
        if types:
            out[f] = types
    return out


def _clear_mame_game_cfg(e: Edit):
    import xml.etree.ElementTree as ET
    root = ET.parse(e.path).getroot()
    types = set(e.values["reset to default"].split(", "))
    for inp in root.iter("input"):
        for port in list(inp.findall("port")):
            if port.get("type") in types:
                for seq in port.findall("newseq"):
                    port.remove(seq)
                if len(port) == 0 and not port.get("value"):
                    inp.remove(port)
    ET.indent(root)
    e.path.write_text('<?xml version="1.0"?>\n' + ET.tostring(root, encoding="unicode") + "\n", encoding="utf-8")


# ---- "[Section] Key = Value" files (DuckStation settings.ini, PCSX2.ini). Keys are "Section/Key".
def read_inisec(path: Path) -> dict:
    out, sec = {}, ""
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
            out[f"{sec}/{k.strip()}"] = v.strip()
    return out


def _apply_inisec(e: Edit):
    p = e.path
    lines = p.read_text(encoding="utf-8", errors="replace").splitlines() if p.exists() else []
    todo: dict[str, dict] = {}
    for key, v in e.values.items():
        sec, k = key.split("/", 1)
        todo.setdefault(sec, {})[k] = v
    out, sec = [], None

    def flush(section):
        for k, v in todo.pop(section, {}).items():
            out.append(f"{k} = {v}")
    for line in lines:
        s = line.strip()
        if s.startswith("[") and s.endswith("]"):
            if sec is not None:
                flush(sec)
            sec = s[1:-1].strip()
        elif sec is not None and "=" in s and not s.startswith((";", "#")):
            k = s.split("=", 1)[0].strip()
            if k in todo.get(sec, {}):
                line = f"{k} = {todo[sec].pop(k)}"
        out.append(line)
    if sec is not None:
        flush(sec)
    for section in list(todo):
        out += ["", f"[{section}]"]
        flush(section)
    p.write_text("\n".join(out) + "\n", encoding="utf-8")


def _apply_mame_cfg(e: Edit):
    """Set input port sequences in a MAME cfg file (XML), keeping everything else.
    default.cfg gets missing ports added; a game's cfg only has its existing ports changed."""
    import xml.etree.ElementTree as ET
    p = e.path
    if p.exists():
        root = ET.parse(p).getroot()
    else:
        root = ET.Element("mameconfig", version="10")
    is_default = p.name.lower() == "default.cfg"
    system = next((s for s in root.findall("system") if s.get("name") == "default"), None) if is_default         else next(iter(root.findall("system")), None)
    if system is None:
        system = ET.SubElement(root, "system", name="default" if is_default else p.stem)
    inp = system.find("input")
    if inp is None:
        inp = ET.SubElement(system, "input")
    for port_type, seq in e.values.items():
        ports = [x for x in inp.findall("port") if x.get("type") == port_type]
        if not ports and not is_default:
            continue
        if not ports:
            ports = [ET.SubElement(inp, "port", type=port_type)]
        for port in ports:
            for old in port.findall("newseq"):
                port.remove(old)
            ET.SubElement(port, "newseq", type="standard").text = seq
    ET.indent(root)
    p.write_text('<?xml version="1.0"?>' + chr(10) + ET.tostring(root, encoding="unicode") + chr(10), encoding="utf-8")


def apply_edit(e: Edit, backup: Backup | None = None):
    """Write one edit. The file is backed up first (into [backup], or a backup of its own)."""
    p = e.path
    p.parent.mkdir(parents=True, exist_ok=True)
    (backup or Backup(p.parent.name)).save(p)
    if e.style in ("mamecfg_clear", "inisec"):
        (_clear_mame_game_cfg if e.style == "mamecfg_clear" else _apply_inisec)(e)
        log.info("emulator fix: wrote %s (%s)", p, ", ".join(f"{k}={v}" for k, v in e.values.items()))
        return
    if e.style == "mamecfg":
        _apply_mame_cfg(e)
        log.info("emulator fix: wrote %s (%s)", p, ", ".join(f"{k}={v}" for k, v in e.values.items()))
        return
    lines = p.read_text(encoding="utf-8", errors="replace").splitlines() if p.exists() else []
    todo = dict(e.values)
    for i, line in enumerate(lines):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        key = s.split("=", 1)[0].strip() if e.style == "cfg" else s.split(None, 1)[0]
        if key in todo:
            lines[i] = _fmt(key, todo.pop(key), e.style)
    lines += [_fmt(k, v, e.style) for k, v in todo.items()]
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    log.info("emulator fix: wrote %s (%s)", p, ", ".join(f"{k}={v}" for k, v in e.values.items()))


def _fmt(k, v, style):
    return f'{k} = "{v}"' if style == "cfg" else f"{k:<25} {v}"


def is_running(exe: str) -> bool:
    name = os.path.basename(exe)
    if sys.platform == "win32":
        try:
            out = subprocess.run(["tasklist", "/FI", f"IMAGENAME eq {name}", "/NH"], capture_output=True,
                                 text=True, timeout=3, creationflags=0x08000000).stdout
            return name.lower() in out.lower()
        except (OSError, subprocess.SubprocessError):
            return False
    try:
        return subprocess.run(["pgrep", "-x", name], capture_output=True, timeout=3).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


# ---------------------------------------------------------------------------------- RetroArch
_probe_cache: dict = {}


def core_ports(core_dll: Path, game: str | None = None) -> list:
    """Controller types per port, asked from the core itself (in a child process). Arcade cores
    (FinalBurn Neo) only list them once a game is loaded: pass [game] for those."""
    key = (str(core_dll), core_dll.stat().st_mtime if core_dll.exists() else 0, game)
    if key in _probe_cache:
        return _probe_cache[key]
    ports = []
    try:
        out = subprocess.run([sys.executable, "-m", "lightgun_host.coreprobe", str(core_dll)] + ([game] if game else []),
                             capture_output=True, text=True, timeout=60 if game else 20,
                             cwd=str(Path(__file__).resolve().parent.parent),
                             creationflags=0x08000000 if sys.platform == "win32" else 0).stdout
        line = next((l for l in out.splitlines() if l.startswith("PORTS=")), None)
        ports = json.loads(line[6:]) if line else []
    except (OSError, ValueError, subprocess.SubprocessError) as e:
        log.warning("core probe failed for %s: %s", core_dll, e)
    _probe_cache[key] = ports
    return ports


def pick_gun(ports: list, game: str, ext: str):
    """(port number 1-based, device id, device name) for this game, or None."""
    guns = [(n + 1, t["id"], t["desc"]) for n, p in enumerate(ports) for t in p
            if t["id"] & 0xFF == 4 or looks_like_gun_device(t["desc"])]
    if not guns:
        return None
    g = game.lower()
    prefs = (("justifier",) if any(k in g for k in KONAMI_GUN_GAMES) else ()) + \
            (("light phaser", "phaser") if ext in (".sms", ".sg") else ()) + GUN_PREFERENCE
    for pref in prefs:
        for port, did, name in guns:  # lowest port first
            if pref in name.lower():
                return port, did, name
    return guns[0]


# RetroArch gun function -> (key suffix, value) added when nothing the phone can press is bound
RA_ADD = {"gun_trigger": ("_mbtn", "1"), "gun_offscreen_shot": ("_mbtn", "2"), "gun_aux_a": ("_mbtn", "3"),
          "gun_aux_b": ("", "space"), "gun_start": ("", "enter"), "gun_select": ("", "rshift")}


MAME_CORE_GUN_OPTION = "mame_lightgun_mode"


is_mame_core = bindread.is_mame_core
# keys handed to the phone when its usual key is taken by a RetroArch hotkey or another binding
RA_FREE_KEYS = ("space", "b", "n", "m", "v", "c", "g", "h", "j", "k", "l", "t", "y", "u", "i", "o", "p")


def _ra_resolve(cfg: dict, binds: dict, port: int, mame_core: bool) -> dict:
    """Make each key the phone presses do exactly one thing. Another player's binding on the same key
    is cleared; a hotkey (fast-forward on Space...) is never changed - the phone moves to a free key."""
    for _ in range(8):
        merged = {**cfg, **binds}
        clashes = bindread.ra_conflicts(merged, port, mame_core)
        if not clashes:
            break
        used = set(bindread.ra_keyboard_binds(merged).values())
        for b, carrier, other in clashes:
            if other.startswith("input_player"):
                binds[other] = "nul"
            else:
                binds[carrier] = next(k for k in RA_FREE_KEYS if k not in used)
                used.add(binds[carrier])
    return binds


def plan_retroarch(exe: str) -> FixPlan:
    plan = FixPlan("RetroArch", exe)
    rp = emulocate.retroarch_paths(exe, emucheck._read_kv)
    if rp is None:
        plan.notes.append("RetroArch's settings file wasn't found. Start RetroArch once, quit it, and try again.")
        return plan
    cfg_path = rp.cfg
    cfg = emucheck._read_kv(cfg_path, "=")
    content, core_path = emucheck._ra_current_content(rp, cfg)
    if not content or not core_path:
        plan.notes.append("Load the game in RetroArch once, then fix again (the fix is per game).")
        return plan
    core = emucheck._ra_core_name(rp.info, core_path)
    dll = Path(core_path.replace("\\", "/"))
    if not dll.exists():
        dll = rp.cores / dll.name
    game = Path(content.replace("\\", "/")).stem
    ext = Path(content).suffix.lower()
    opt = rp.overrides / core / f"{core}.opt"
    if is_mame_core(core):
        # The MAME core has no light gun device type: it reads the gun on port 1 once its
        # "Lightgun Mode" option is on (it ships off: mame_lightgun_mode = none).
        port, name = 1, "light gun (MAME core option)"
        if emucheck._read_kv(opt, "=").get(MAME_CORE_GUN_OPTION) != "lightgun":
            plan.edits.append(Edit(opt, {MAME_CORE_GUN_OPTION: "lightgun"}, "cfg", "closed",
                                   "turn on the MAME core's light gun"))
    else:
        ports = core_ports(dll)
        if not any(t["id"] & 0xFF == 4 for p in ports for t in p):
            ports = core_ports(dll, content.split("#", 1)[0].replace("\\", "/"))   # arcade cores: per game
        pick = pick_gun(ports, game, ext)
        if not pick:
            plan.notes.append(f"{core} has no light gun device for {game} - this core can't be used with a gun.")
            return plan
        port, did, name = pick
        # 1) device type, saved as a game remap (safe while RetroArch runs; used on the next load)
        remaps_dir = rp.remaps
        rmp = remaps_dir / core / f"{game}.rmp"
        current = {**{k: v for k, v in cfg.items() if k.startswith("input_libretro_device_p")},
                   **emucheck._read_kv(remaps_dir / core / f"{core}.rmp", "="), **emucheck._read_kv(rmp, "=")}
        vals = {f"input_libretro_device_p{port}": str(did), f"input_remap_port_p{port}": str(port - 1)}
        for n in range(1, 9):
            try:
                d = int(current.get(f"input_libretro_device_p{n}", "1"))
            except ValueError:
                continue
            if n != port and d & 0xFF == 4:
                vals[f"input_libretro_device_p{n}"] = str(JOYPAD)  # only one gun port per game
        have = emucheck._read_kv(rmp, "=")
        if any(have.get(k) != v for k, v in vals.items()):
            plan.edits.append(Edit(rmp, vals, "cfg", "now", f"port {port} = {name} for {game}"))
    # 2) port binds + input driver in retroarch.cfg (RetroArch rewrites it on exit -> wait until closed)
    #    Buttons you've already bound are kept; only ones the phone can't press get a binding.
    mame_core = is_mame_core(core)
    main_cfg = cfg
    cfg = bindread.ra_effective_cfg(rp, cfg)          # what RetroArch really uses for this core/game
    binds = {f"input_player{port}_mouse_index": "0"}
    for func, (suffix, value) in RA_ADD.items():
        if mame_core and func in ("gun_start", "gun_select"):
            continue                       # the MAME core takes Start/Coin from the RetroPad
        if bindread.ra_bind(cfg, port, func)[0] is None:
            binds[f"input_player{port}_{func}{suffix}"] = value
    if mame_core:
        for b, default in (("start", "enter"), ("select", "rshift")):
            if bindread.ra_key(cfg.get(f"input_player{port}_{b}", "nul")) is None:
                binds[f"input_player{port}_{b}"] = default
    binds = _ra_resolve(cfg, binds, port, mame_core)
    # Windows: "raw" only reads real mice and drops the phone's (tested: no crosshair, no shots)
    if sys.platform == "win32" and cfg.get("input_driver") != "dinput":
        binds["input_driver"] = "dinput"
    # lets RetroSight ask RetroArch which game is running (GET_STATUS on localhost), so a new
    # game is picked up instead of the history's last-played entry
    if not emucheck.ra_network_cmd_on(cfg):
        binds["network_cmd_enable"] = "true"
    need = {k: v for k, v in binds.items() if cfg.get(k) != v}
    if mame_core:
        # the MAME core reads the RetroPad too, so its Start/Coin changes would break the gun cores:
        # they go into the MAME core's own override file (read at load, never rewritten by RetroArch)
        core_only = {k: v for k, v in need.items() if k.startswith("input_player")}
        if core_only:
            plan.edits.append(Edit(rp.overrides / core / f"{core}.cfg", core_only, "cfg", "now",
                                   "MAME core only: each phone button presses one thing"))
        need = {k: v for k, v in need.items() if k not in core_only}
    need = {k: v for k, v in need.items() if main_cfg.get(k) != v}
    if need:
        plan.edits.append(Edit(cfg_path, need, "cfg", "closed", f"gun buttons for port {port}"))
    # 3) show the crosshair (core options file, also rewritten on exit)
    o = emucheck._read_kv(opt, "=")
    cross = {}
    for k, v in o.items():
        if re.search(r"(gun_cursor|crosshair)$", k) and v in ("disabled", "off"):
            cross[k] = "enabled" if v == "disabled" else "cross"
    if cross:
        plan.edits.append(Edit(opt, cross, "cfg", "closed", "show where you aim"))
    if not plan.edits:
        plan.notes.append(f"{game}: already set up (port {port} = {name}).")
    return plan


# ---------------------------------------------------------------------------------- MAME
def plan_mame(exe: str) -> FixPlan:
    plan = FixPlan("MAME", exe)
    ini = emulocate.mame_ini(exe)
    if ini is None:
        ini = emulocate.mame_ini_target(exe)
        plan.create_mame_ini = True
    o = emucheck._read_kv(ini, None)
    want = {"lightgun": "1", "lightgun_device": "lightgun"}
    linux = sys.platform.startswith("linux")
    if sys.platform == "win32":
        want["lightgunprovider"] = "win32"
    elif linux:
        want["lightgunprovider"] = "x11"   # sees each phone as its own light gun (players 1 and 2)
        # the x11 provider only uses guns listed by name (input_x11.cpp: lightgun_index1..8)
        want["lightgun_index1"] = '"Phone Lightgun 1"'
        want["lightgun_index2"] = '"Phone Lightgun 2"'
    if not linux and o.get("joystick", "1") != "1":
        want["joystick"] = "1"     # player 2 aims with a (virtual) joystick
    need = {k: v for k, v in want.items() if o.get(k) != v}
    if need:
        plan.edits.append(Edit(ini, need, "ini", "now", "restart MAME afterwards"))
    # MAME rewrites default.cfg on exit -> these wait until it's closed
    cfg_dir = Path(o.get("cfg_directory", "cfg"))
    cfg_dir = cfg_dir if cfg_dir.is_absolute() else Path(exe).parent / cfg_dir
    cfg = cfg_dir / "default.cfg"
    # Buttons, start and coin: keep whatever is bound; only where a function has nothing the phone
    # can press, add MAME's own default key to it.
    mouse_on = o.get("mouse", "0") == "1"
    defaults, _ = bindread.mame_defaults(exe)
    user = bindread.mame_cfg_ports(cfg)
    ports = {}
    for table, gun in ((bindread.MAME_P1, 1), (bindread.MAME_P2, 2)):
        for ptype in table.values():
            seq = user.get(ptype, defaults.get(ptype, MAME_BUTTONS[ptype]))
            if bindread.mame_usable(seq, gun, linux, mouse_on) is None:
                ports[ptype] = _mame_add(seq, bindread.MAME_ADD[ptype])
    # Windows: player 2 aims with the virtual pad's right stick (on Linux it's simply light gun 2)
    if not linux and any(user.get(k) != v for k, v in MAME_P2_INPUT.items()):
        ports.update(MAME_P2_INPUT)
    if ports:
        why = [w for w, ok in (("adds a key the phone can press where nothing usable was bound",
                                any(k not in MAME_P2_INPUT for k in ports)),
                               ("player 2 aims with the virtual pad's right stick",
                                any(k in MAME_P2_INPUT for k in ports))) if ok]
        plan.edits.append(Edit(cfg, ports, "mamecfg", "closed", "; ".join(why)))
    # games with their own mapping: same rule, in that game's file
    for f in sorted(cfg_dir.glob("*.cfg")) if cfg_dir.exists() else []:
        if f.name.lower() == "default.cfg":
            continue
        fix = {}
        for ptype, seq in bindread.mame_cfg_ports(f).items():
            gun = 2 if ptype in bindread.MAME_P2.values() else 1
            if ptype in bindread.MAME_ADD and bindread.mame_usable(seq, gun, linux, mouse_on) is None:
                fix[ptype] = _mame_add(seq, bindread.MAME_ADD[ptype])
        if fix:
            plan.edits.append(Edit(f, fix, "mamecfg", "closed", f"{f.stem}: its own mapping had no phone button"))
    if not plan.edits:
        plan.notes.append("MAME is already set up for the gun (players 1 and 2).")
    return plan


def _mame_add(seq: str, code: str) -> str:
    """Keep the user's binding and add [code] as another way to press it."""
    seq = (seq or "").strip()
    return code if seq in ("", "NONE", "DEFAULT") else f"{seq} OR {code}"


# ---------------------------------------------------------------------------------- DuckStation / PCSX2
def _documents() -> Path:
    home = Path.home()
    for d in (Path(os.environ.get("USERPROFILE", home)) / "Documents",
              Path(os.environ.get("USERPROFILE", home)) / "OneDrive" / "Documents"):
        if d.exists():
            return d
    return home / "Documents"


def duckstation_ini(exe: str) -> Path:
    d = Path(exe).parent
    if (d / "portable.txt").exists() or (d / "settings.ini").exists():
        return d / "settings.ini"
    if sys.platform == "win32":
        return _documents() / "DuckStation" / "settings.ini"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "DuckStation" / "settings.ini"
    return Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "duckstation" / "settings.ini"


def pcsx2_ini(exe: str) -> Path:
    d = Path(exe).parent
    if (d / "portable.ini").exists() or (d / "portable.txt").exists():
        return d / "inis" / "PCSX2.ini"
    if sys.platform == "win32":
        return _documents() / "PCSX2" / "inis" / "PCSX2.ini"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "PCSX2" / "inis" / "PCSX2.ini"
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "PCSX2" / "inis" / "PCSX2.ini"


# bind names from duckstation src/core/guncon.cpp; values match presets["duckstation"]
DUCKSTATION_GUN = {"Pad1/Type": "GunCon", "Pad1/Pointer": "Pointer-0", "Pad1/Trigger": "Pointer-0/LeftButton",
                   "Pad1/ShootOffscreen": "Keyboard/R", "Pad1/A": "Pointer-0/RightButton",
                   "Pad1/B": "Pointer-0/MiddleButton"}
# bind names from pcsx2 USB/usb-lightgun/guncon2.cpp; values match presets["pcsx2"]
PCSX2_GUN = {"USB1/Type": "guncon2", "USB1/guncon2_Trigger": "Pointer-0/LeftButton",
             "USB1/guncon2_ShootOffscreen": "Keyboard/R", "USB1/guncon2_A": "Pointer-0/RightButton",
             "USB1/guncon2_B": "Pointer-0/MiddleButton", "USB1/guncon2_Start": "Keyboard/Return",
             "USB1/guncon2_Select": "Keyboard/Backspace"}


def _plan_inisec(name: str, exe: str, ini: Path, want: dict, why: str, port: str | None = None,
                 default_port: str = "") -> FixPlan:
    """Set up the gun port, keeping any button already bound to something the phone can press."""
    plan = FixPlan(name, exe)
    have = bindread.read_ini_multi(ini)
    need = {}
    for k, v in want.items():
        if port and port != default_port:          # the user's gun is on another port: fix that one
            k = port + k[len(default_port):]
        vals = have.get(k, [])
        if k.endswith("/Type"):
            if port is None:
                need[k] = v
        elif k.endswith("/Pointer"):
            if not vals:
                need[k] = v
        elif bindread._first_usable(vals) is None:
            need[k] = v
    if need:
        # these emulators save their settings when they close: edit afterwards
        plan.edits.append(Edit(ini, need, "inisec", "closed", why))
    else:
        plan.notes.append(f"{name}: gun already set up.")
    return plan


def plan_duckstation(exe: str) -> FixPlan:
    ini = duckstation_ini(exe)
    port = bindread.ds_gun_pad(bindread.read_ini_multi(ini))
    return _plan_inisec("DuckStation", exe, ini, DUCKSTATION_GUN, f"GunCon on {port or 'Pad1'}", port, "Pad1")


def plan_pcsx2(exe: str) -> FixPlan:
    ini = pcsx2_ini(exe)
    port = bindread.pcsx2_gun_port(bindread.read_ini_multi(ini))
    return _plan_inisec("PCSX2", exe, ini, PCSX2_GUN, f"GunCon 2 on {port or 'USB1'}", port, "USB1")


# ---------------------------------------------------------------------------------- Supermodel
def plan_supermodel(exe: str) -> FixPlan:
    plan = FixPlan("Supermodel", exe)
    if not bindread.is_supermodel(exe):
        plan.notes.append("Automatic setup isn't available for the Sega Model 2 emulator "
                          "(it keeps its buttons in a binary file per game).")
        return plan
    ini = bindread.supermodel_ini(exe)
    if not ini.exists():
        plan.notes.append(f"Supermodel's settings file wasn't found ({ini}). Run Supermodel once and try again.")
        return plan
    have = {k.split("/", 1)[1]: v[-1] for k, v in bindread.read_ini_multi(ini).items() if k.startswith("Global/")}
    need = {}
    for key, add in bindread.SM_ADD.items():
        cur = have.get("Input" + key, "NONE")
        ok = bindread.sm_has_axis(cur, add[6]) if add.endswith("AXIS") else bindread.sm_usable(cur) is not None
        if not ok:
            new_val = add if cur.strip() in ("", "NONE") else f"{cur},{add}"
            need["Global/Input" + key] = f'"{new_val}"'
    if need:
        # Supermodel only writes its .ini when you configure inputs from its menu: safe to edit now
        plan.edits.append(Edit(ini, need, "inisec", "now", "gun aim and buttons on the mouse (restart Supermodel)"))
    else:
        plan.notes.append("Supermodel: gun already set up.")
    return plan


# ---------------------------------------------------------------------------------- Dolphin
DOLPHIN_DEVICE = {"win32": "DInput/0/Keyboard Mouse", "darwin": "Quartz/0/Keyboard & Mouse"}
DOLPHIN_IR = {"IR/Up": "`Cursor Y-`", "IR/Down": "`Cursor Y+`", "IR/Left": "`Cursor X-`", "IR/Right": "`Cursor X+`"}


def plan_dolphin(exe: str) -> FixPlan:
    plan = FixPlan("Dolphin", exe)
    ini = bindread.dolphin_ini(exe)
    if not ini.exists():
        # no file = Dolphin's own defaults (mouse pointer, A = left click, B = right click): nothing to do.
        # Writing a partial file would unbind everything else, so don't.
        plan.notes.append("Dolphin: using its default Wii Remote (pointer = mouse): nothing to change.")
        return plan
    have = {k.split("/", 1)[1]: v[-1] for k, v in bindread.read_ini_multi(ini).items() if k.startswith("Wiimote1/")}
    need = {}
    if not have.get("Device"):
        need["Wiimote1/Device"] = DOLPHIN_DEVICE.get(sys.platform, "XInput2/0/Virtual core pointer")
    if have.get("Source", "1") not in ("1",):
        need["Wiimote1/Source"] = "1"                  # emulated Wii Remote
    for key, expr in {**bindread.DOLPHIN_DEFAULTS, **DOLPHIN_IR}.items():
        cur = have.get(key, "")
        ok = "Cursor" in cur if key.startswith("IR/") else bindread.dolphin_action(cur) is not None
        if not ok:
            need["Wiimote1/" + key] = expr if not cur else f"{cur} | {expr}"
    if need:
        plan.edits.append(Edit(ini, need, "inisec", "closed", "Wii Remote 1 pointer and buttons on the mouse"))
    else:
        plan.notes.append("Dolphin: Wii Remote 1 already set up.")
    return plan


PLANNERS = {"retroarch": plan_retroarch, "mame": plan_mame, "duckstation": plan_duckstation, "pcsx2": plan_pcsx2,
            "model2_supermodel": plan_supermodel, "dolphin": plan_dolphin}


def plan_for(exe: str | None) -> FixPlan | None:
    preset = preset_for(exe)
    fn = PLANNERS.get(preset or "")
    return fn(exe) if fn else None


def create_mame_ini(exe: str, target: Path):
    """Let MAME write its full default mame.ini (so every option is listed), then we edit it."""
    try:
        subprocess.run([exe, "-createconfig"], cwd=str(target.parent if target.parent.exists() else Path(exe).parent),
                       capture_output=True, timeout=30,
                       creationflags=0x08000000 if sys.platform == "win32" else 0)
    except (OSError, subprocess.SubprocessError) as e:
        log.warning("mame -createconfig failed: %s (writing a short mame.ini instead)", e)


def permission_hint(path: Path) -> str:
    p = str(path).lower()
    if sys.platform == "win32" and ("program files" in p or "windowsapps" in p):
        return (f"Windows doesn't let normal programs change files in {path.parent}. Either move the emulator "
                f"to a folder like C:\\Emulators, or right-click start-host.bat > Run as administrator.")
    return f"No permission to change {path}. Check the file isn't read-only, or run start-host.bat as administrator."


def apply(plan: FixPlan, running: bool) -> tuple[list[str], list[Edit], list[str]]:
    """Apply what can be applied now. Returns (files saved, edits still waiting, error messages)."""
    done, waiting, errors = [], [], []
    backup = Backup(plan.emulator)
    if plan.create_mame_ini and plan.edits and not (running and plan.edits[0].when == "closed"):
        backup.save(plan.edits[0].path)            # noted as new: Undo removes it again
        create_mame_ini(plan.exe, plan.edits[0].path)
    for e in plan.edits:
        if e.when == "closed" and running:
            waiting.append(e)
            continue
        try:
            apply_edit(e, backup)
            done.append(e.path.name)
        except PermissionError:
            errors.append(permission_hint(e.path))
            log.error("no permission to write %s", e.path)
        except OSError as ex:
            errors.append(f"Couldn't save {e.path.name}: {ex}")
            log.error("could not write %s: %s", e.path, ex)
    return done, waiting, errors
