import sys

import pytest

from lightgun_host import protocol as P
from lightgun_host.inject import Injector, Monitor, NullBackend
from lightgun_host.inject.gamepad import PAD_BUTTONS, NullGamepad
from lightgun_host.presets import PRESETS, apply_preset

F = P.FLAG_CALIBRATED | P.FLAG_ON_SCREEN


def pkt(seq, x=0.5, y=0.5, buttons=0, flags=F):
    return P.AimPacket(0, seq, 0, x, y, flags, buttons)


@pytest.mark.parametrize("name", list(PRESETS))
def test_presets_are_valid(name):
    p = PRESETS[name]
    assert p["output"] in ("mouse", "gamepad", "both")
    for act in list(p["buttons"].values()) + [p["offscreen_trigger"]]:
        kind, _, arg = act.partition(":")
        assert kind in ("mouse", "key", "offscreen", "pad", "none"), act
        if kind in ("mouse", "offscreen"):
            assert arg in ("left", "right", "middle")
        if kind == "pad":
            assert arg in PAD_BUTTONS
        if kind == "key" and sys.platform == "win32":
            from lightgun_host.inject.windows import _vk
            _vk(arg)  # raises on unknown key names
    d = {}
    apply_preset(d, name)
    assert d["preset"] == name and d["buttons"] == p["buttons"]


def test_click_lands_after_move():
    be = NullBackend()
    inj = Injector(be, be.monitors()[0], {"trigger": "mouse:left"}, "mouse:right")
    inj.update(pkt(1, 0.3, 0.4, P.BTN_TRIGGER))
    assert be.events[0] == ("move", 0, 0.3, 0.4)
    assert be.events[1] == ("action", 0, "mouse:left", True)


def test_offscreen_reload_action_holds_cursor_out():
    be = NullBackend()
    inj = Injector(be, be.monitors()[0], {"trigger": "mouse:left", "reload": "offscreen:left"}, "offscreen:left")
    inj.update(pkt(1, 0.5, 0.5, P.BTN_RELOAD))
    inj.update(pkt(2, 0.6, 0.6, P.BTN_RELOAD))   # still held: cursor must stay off-screen
    inj.update(pkt(3, 0.7, 0.7, 0))
    inj.update(pkt(4, 0.8, 0.8, 0))
    assert be.events == [
        ("move", 0, 0.5, 0.5), ("move", 0, 0.0, 0.0), ("action", 0, "mouse:left", True),
        ("action", 0, "mouse:left", False), ("move", 0, 0.8, 0.8)]


def test_gamepad_output_and_buttons():
    be, pad = NullBackend(), NullGamepad()
    p = PRESETS["gamepad"]
    inj = Injector(be, be.monitors()[0], p["buttons"], p["offscreen_trigger"], "gamepad", pad)
    inj.update(pkt(1, 0.25, 0.75, P.BTN_TRIGGER))
    inj.update(pkt(2, 0.25, 0.75, 0))
    assert not [e for e in be.events if e[0] == "move"]
    assert pad.events == [("aim", 0, 0.25, 0.75), ("pad", 0, "rt", True), ("aim", 0, 0.25, 0.75),
                          ("pad", 0, "rt", False)]


def test_monitor_geometry_refresh(monkeypatch):
    import lightgun_host.inject as inj_mod
    be = NullBackend()
    inj = Injector(be, be.monitors()[0], {}, "")
    be.mons = [Monitor("null:1280x720", "null", 0, 0, 1280, 720, True)]  # emulator changed resolution
    monkeypatch.setattr(inj_mod, "MONITOR_REFRESH_S", -1)
    inj.update(pkt(1))
    assert inj.monitor.w == 1280


def test_bad_mapping_does_not_wedge():
    be = NullBackend()
    inj = Injector(be, be.monitors()[0], {"trigger": "pad:rt"}, "")  # no gamepad attached
    inj.update(pkt(1, buttons=P.BTN_TRIGGER))
    inj.update(pkt(2, buttons=0))
    assert inj._held == {}


def test_auto_preset_mapping():
    from lightgun_host.autopreset import preset_for
    assert preset_for(r"E:\Games\ROMS\mame.exe") == "mame"
    assert preset_for(r"C:\RetroArch-Win64\retroarch.exe") == "retroarch"
    assert preset_for("/usr/bin/retroarch") == "retroarch"
    assert preset_for(r"C:\x\duckstation-qt-x64-ReleaseLTCG.exe") == "duckstation"
    assert preset_for(r"C:\x\pcsx2-qt.exe") == "pcsx2"
    assert preset_for(r"C:\x\Dolphin.exe") == "dolphin"
    assert preset_for(r"C:\x\EMULATOR_MULTICPU.EXE") == "model2_supermodel"
    assert preset_for(r"C:\Windows\explorer.exe") is None
    assert preset_for(None) is None


def test_player2_on_gamepad_keeps_player1_mouse():
    from lightgun_host import protocol as P
    from lightgun_host.inject import Injector, Monitor, NullBackend
    from lightgun_host.inject.gamepad import NullGamepad
    from lightgun_host.presets import player_buttons
    be, pad = NullBackend(), NullGamepad()
    inj = Injector(be, Monitor("m", "m", 0, 0, 1920, 1080, True), *player_buttons("mame", 0, "mouse"), "mouse", pad)
    inj.gun_output[1] = "gamepad"
    inj.gun_buttons[1] = player_buttons("mame", 1, "gamepad")

    seq = iter(range(1, 100))

    def pkt(gid, x, y, buttons=0):
        return P.AimPacket(gid, next(seq), 0, x, y, P.FLAG_TRACKING | P.FLAG_ON_SCREEN | P.FLAG_CALIBRATED, buttons)

    inj.update(pkt(0, 0.25, 0.5))
    inj.update(pkt(1, 0.75, 0.5))
    inj.update(pkt(1, 0.75, 0.5, P.BTN_START))
    assert ("aim", 1, 0.75, 0.5) in pad.events          # player 2 aims with the pad
    assert not any(e[0] == "aim" and e[1] == 0 for e in pad.events)
    assert any("key:2" in str(e) for e in be.events)     # player 2 START = MAME key 2
