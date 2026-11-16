"""Persistent host config: pairing key, port, per-display screen sizes, button map."""
from __future__ import annotations

import json
import os
import secrets
import sys
from pathlib import Path

DEFAULT_BUTTONS = {
    "trigger": "mouse:left",
    "reload": "mouse:right",
    "a": "mouse:middle",
    "b": "key:space",
    "start": "key:enter",
    "select": "key:esc",
}


def config_dir() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home()))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "phone-lightgun"


class Store:
    def __init__(self, path: Path | None = None):
        self.path = path or (config_dir() / "config.json")
        self.data = {}
        if self.path.exists():
            try:
                self.data = json.loads(self.path.read_text())
            except json.JSONDecodeError:
                self.data = {}
        changed = False
        if "key" not in self.data:
            self.data["key"] = secrets.token_hex(16)
            changed = True
        self.data.setdefault("port", 47800)
        self.data.setdefault("screens", {})
        self.data.setdefault("buttons", dict(DEFAULT_BUTTONS))
        self.data.setdefault("offscreen_trigger", "mouse:right")
        self.data.setdefault("monitor", None)
        self.data.setdefault("preset", "generic")
        self.data.setdefault("output", "mouse")
        if changed:
            self.save()

    @property
    def key(self) -> bytes:
        return bytes.fromhex(self.data["key"])

    def regenerate_key(self):
        self.data["key"] = secrets.token_hex(16)
        self.save()

    def screen_size(self, display_id: str):
        s = self.data["screens"].get(display_id)
        if s is None and ":" in display_id:
            # same monitor at another resolution (emulators switch modes): physical size is unchanged
            dev = display_id.rsplit(":", 1)[0]
            s = next((v for k, v in self.data["screens"].items() if k.rsplit(":", 1)[0] == dev), None)
        return (s["w_m"], s["h_m"]) if s else None

    def set_screen_size(self, display_id: str, w_m: float, h_m: float, source: str):
        self.data["screens"][display_id] = {"w_m": w_m, "h_m": h_m, "source": source}
        self.save()

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=2))
        tmp.replace(self.path)
