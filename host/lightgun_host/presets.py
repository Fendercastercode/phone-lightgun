"""Per-emulator output presets.

Action vocabulary (used in `buttons` and `offscreen_trigger`):
  mouse:left|right|middle      mouse button
  key:<name>                   keyboard key (enter, esc, space, tab, f1..f12, a..z, 0..9, arrows)
  offscreen:left|right         move the cursor off the game area (monitor corner) and click;
                               for emulators whose reload is "shoot off-screen"
  pad:<button>                 virtual gamepad button (a b x y lb rb lt rt start back ls rs)
  none                         ignore

`output`: "mouse" (absolute system cursor), "gamepad" (aim on the right stick of a virtual
pad, one pad per gun) or "both".

The phone already turns "shoot off-screen" and "flick" into the reload button, so presets
only need to say what reload means to that emulator.
"""
from __future__ import annotations

PRESETS: dict[str, dict] = {
    "generic": {
        "label": "Generic (absolute mouse)",
        "output": "mouse",
        "buttons": {"trigger": "mouse:left", "reload": "mouse:right", "a": "mouse:middle",
                    "b": "key:space", "start": "key:enter", "select": "key:esc"},
        "offscreen_trigger": "mouse:right",
        "notes": "Any emulator or PC game with mouse aiming.",
    },
    "retroarch": {
        "label": "RetroArch (lightgun cores)",
        "output": "mouse",
        # RetroArch default binds: Lightgun Trigger = mouse 1, Aux A = mouse 2, Aux B = mouse 3,
        # Start = Enter, Select = RShift; "Gun Reload / Offscreen shot" bound to mouse 2 in the
        # core's lightgun settings.
        "buttons": {"trigger": "mouse:left", "reload": "mouse:right", "a": "mouse:middle",
                    "b": "key:space", "start": "key:enter", "select": "key:rshift"},
        "offscreen_trigger": "mouse:right",
        "labels": {"trigger": "TRIGGER", "reload": "RELOAD", "a": "AUX A", "b": "AUX B",
                   "start": "START", "select": "SELECT"},
        "notes": "Input driver dinput (Windows; raw ignores the phone) or udev (Linux). Device type Lightgun per port.",
    },
    "mame": {
        "label": "MAME",
        "output": "mouse",
        # MAME defaults (src/emu/inpttype.ipp): P1 Button 1 = LCtrl / gun button 1, Button 2 = LAlt /
        # gun button 2, Button 3 = Space (no gun code!), Start 1 = 1, Coin 1 = 5. The middle mouse
        # button is NOT bound in light gun mode, so the extra buttons go out as keys.
        # Reload in gun games = shoot off-screen.
        "buttons": {"trigger": "mouse:left", "reload": "offscreen:left", "a": "key:lalt",
                    "b": "key:space", "start": "key:1", "select": "key:5"},
        "offscreen_trigger": "offscreen:left",
        "labels": {"trigger": "BUTTON 1", "reload": "OFF-SCREEN SHOT", "a": "BUTTON 2", "b": "BUTTON 3",
                   "start": "1P START", "select": "COIN 1"},
        "notes": "mame.ini: lightgun 1, lightgunprovider win32 (Linux: x11). Start=1, Coin=5.",
    },
    "duckstation": {
        "label": "DuckStation (PS1 GunCon/Justifier)",
        "output": "mouse",
        # GunCon: Trigger=LMB, A=RMB, B=MMB, "Shoot Offscreen" bindable (we use key R).
        "buttons": {"trigger": "mouse:left", "reload": "key:r", "a": "mouse:right",
                    "b": "mouse:middle", "start": "key:enter", "select": "key:backspace"},
        "offscreen_trigger": "key:r",
        "labels": {"trigger": "TRIGGER", "reload": "OFF-SCREEN", "a": "GUNCON A", "b": "GUNCON B",
                   "start": "START (JUSTIFIER)", "select": "BACK (JUSTIFIER)"},
        "notes": "Fix emulator settings binds the GunCon on port 1 (Shoot Offscreen = R).",
    },
    "pcsx2": {
        "label": "PCSX2 (PS2 GunCon2)",
        "output": "mouse",
        "buttons": {"trigger": "mouse:left", "reload": "key:r", "a": "mouse:right",
                    "b": "mouse:middle", "start": "key:enter", "select": "key:backspace"},
        "offscreen_trigger": "key:r",
        "labels": {"trigger": "TRIGGER", "reload": "OFF-SCREEN", "a": "GUNCON A", "b": "GUNCON B",
                   "start": "START", "select": "SELECT"},
        "notes": "Fix emulator settings binds GunCon2 on USB port 1; calibrate in-game.",
    },
    "flycast": {
        "label": "Flycast (Dreamcast/Naomi/Atomiswave)",
        "output": "mouse",
        # Flycast defaults (core/input/mouse.h, keyboard_device.h): left click = A (trigger), right = B,
        # Return = Start, A key = Coin. Space is fast-forward - never send it.
        "buttons": {"trigger": "mouse:left", "reload": "offscreen:left", "a": "mouse:right",
                    "b": "none", "start": "key:enter", "select": "key:a"},
        "offscreen_trigger": "offscreen:left",
        "labels": {"trigger": "TRIGGER (A)", "reload": "OFF-SCREEN SHOT", "a": "B", "b": "-",
                   "start": "START", "select": "COIN"},
        "notes": "Port A = Light Gun. Reload = shoot off-screen. Buttons are read from Flycast's mapping files.",
    },
    "mesen": {
        "label": "Mesen 2 (NES Zapper / Super Scope / Light Phaser)",
        "output": "mouse",
        "buttons": {"trigger": "mouse:left", "reload": "offscreen:left", "a": "mouse:right",
                    "b": "mouse:middle", "start": "key:enter", "select": "none"},
        "offscreen_trigger": "offscreen:left",
        "labels": {"trigger": "FIRE", "reload": "OFF-SCREEN SHOT", "a": "CURSOR", "b": "TURBO",
                   "start": "PAUSE", "select": "-"},
        "notes": "Set the port to Zapper / Super Scope / Light Phaser. Buttons are read from Mesen's settings.json.",
    },
    "dolphin": {
        "label": "Dolphin (Wii pointer)",
        "output": "mouse",
        "buttons": {"trigger": "mouse:right", "reload": "mouse:middle", "a": "mouse:left",
                    "b": "key:1", "start": "key:e", "select": "key:q"},
        "offscreen_trigger": "mouse:middle",
        "labels": {"trigger": "WII B", "reload": "SHAKE", "a": "WII A", "b": "WII 1",
                   "start": "WII +", "select": "WII -"},
        "notes": "Dolphin's default Wii Remote: A = left click, B = right click, Shake = middle, + = E, - = Q.",
    },
    "model2_supermodel": {
        "label": "Model 2 / Supermodel (Sega arcade)",
        "output": "mouse",
        "buttons": {"trigger": "mouse:left", "reload": "mouse:right", "a": "mouse:middle",
                    "b": "key:space", "start": "key:1", "select": "key:5"},
        "offscreen_trigger": "mouse:right",
        "labels": {"start": "START", "select": "COIN"},
        "notes": "Supermodel: -input-system=rawinput; Model 2: lightgun via mouse in game config.",
    },
    "teknoparrot_demulshooter": {
        "label": "TeknoParrot / DemulShooter (PC arcade)",
        "output": "mouse",
        "buttons": {"trigger": "mouse:left", "reload": "mouse:right", "a": "mouse:middle",
                    "b": "key:space", "start": "key:1", "select": "key:5"},
        "offscreen_trigger": "mouse:right",
        "labels": {"start": "START", "select": "COIN"},
        "notes": "Bind gun to mouse; many titles reload on RMB or off-screen.",
    },
    "gamepad": {
        "label": "Virtual gamepad (right stick = aim)",
        "output": "gamepad",
        "buttons": {"trigger": "pad:rt", "reload": "pad:lt", "a": "pad:a", "b": "pad:b",
                    "start": "pad:start", "select": "pad:back"},
        "offscreen_trigger": "pad:lt",
        "notes": "For emulators that read a pointer from an analog stick (Dolphin IR, PCSX2, "
                 "multi-gun on Windows). Windows needs the ViGEmBus driver + `pip install vgamepad`.",
    },
}


# Player 2+ on Windows/macOS: the system has one cursor, so extra guns aim with a virtual
# gamepad's right stick. Their buttons are the emulator's player-2 keys where it has them.
PAD_P2 = {"trigger": "pad:rt", "reload": "pad:lt", "a": "pad:a", "b": "pad:b",
          "start": "pad:start", "select": "pad:back"}
P2_BUTTONS: dict[str, dict] = {
    # MAME defaults for player 2: Button 1 = A, Button 2 = S, Start = 2, Coin = 6
    "mame": {"buttons": {"trigger": "key:a", "reload": "offscreen:key:a", "a": "key:s", "b": "key:q",
                         "start": "key:2", "select": "key:6"},
             "offscreen_trigger": "offscreen:key:a",
             "labels": {"trigger": "P2 BUTTON 1", "reload": "OFF-SCREEN SHOT", "a": "P2 BUTTON 2",
                        "b": "P2 BUTTON 3", "start": "2P START", "select": "COIN 2"}},
}


_NICE = {"mouse:left": "LEFT CLICK", "mouse:right": "RIGHT CLICK", "mouse:middle": "MIDDLE CLICK",
         "offscreen:left": "OFF-SCREEN SHOT", "offscreen:right": "OFF-SCREEN SHOT"}


def _describe(action: str) -> str:
    if action in _NICE:
        return _NICE[action]
    kind, _, arg = action.partition(":")
    if kind == "key":
        return f"KEY {arg.upper()}"
    if kind == "pad":
        return f"PAD {arg.upper()}"
    return action.upper() if action and action != "none" else "-"


def button_labels(preset: str, gun_id: int = 0, output: str = "mouse") -> dict:
    """What each phone button does in this emulator, for the phone's pad (e.g. A -> 'BUTTON 2')."""
    p = PRESETS.get(preset, PRESETS["generic"])
    buttons, _ = player_buttons(preset, gun_id, output)
    names = dict(P2_BUTTONS[preset].get("labels", {})) if gun_id > 0 and preset in P2_BUTTONS else dict(p.get("labels", {}))
    return {b: names.get(b) or _describe(a) for b, a in buttons.items()}


def player_buttons(preset: str, gun_id: int, output: str) -> tuple[dict, str]:
    """(buttons, offscreen_trigger) for gun [gun_id] (0 = player 1) on this [output]."""
    p = PRESETS.get(preset, PRESETS["generic"])
    if gun_id == 0:
        return dict(p["buttons"]), p["offscreen_trigger"]
    if preset in P2_BUTTONS:
        q = P2_BUTTONS[preset]
        b = dict(q["buttons"])
        if output == "mouse":  # Linux: the gun is its own pointer device -> real mouse buttons
            b.update({"trigger": "mouse:left", "reload": "mouse:right"})
            return b, "offscreen:left"
        return b, q["offscreen_trigger"]
    if output == "mouse":
        return dict(p["buttons"]), p["offscreen_trigger"]
    return dict(PAD_P2), "pad:lt"


def apply_preset(store_data: dict, name: str) -> dict:
    p = PRESETS[name]
    store_data["preset"] = name
    store_data["buttons"] = dict(p["buttons"])
    store_data["offscreen_trigger"] = p["offscreen_trigger"]
    store_data["output"] = p["output"]
    return p
