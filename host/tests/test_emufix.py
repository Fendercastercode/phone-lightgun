import sys

import pytest

from lightgun_host import emufix

GPGX = [[{"desc": "MD Joypad 3 Button", "id": 257}, {"desc": "MS Light Phaser", "id": 260}],
        [{"desc": "MD Joypad 3 Button", "id": 257}, {"desc": "MD Menacer", "id": 516},
         {"desc": "MD Justifiers", "id": 772}, {"desc": "MS Light Phaser", "id": 260}]]
PSX = [[{"desc": "Guncon / G-Con 45", "id": 260}, {"desc": "Justifier", "id": 516}]] * 2
# Mesen's Zapper is RETRO_DEVICE_POINTER (class 6), not RETRO_DEVICE_LIGHTGUN (class 4)
MESEN = [[{"desc": "Standard Controller", "id": 257}, {"desc": "Zapper", "id": 262}]] * 2
# FCEUmm's Zapper is RETRO_DEVICE_MOUSE (class 2)
FCEUMM = [[{"desc": "Gamepad", "id": 513}, {"desc": "Zapper", "id": 258}]] * 2


def test_pick_gun_per_game():
    assert emufix.pick_gun(GPGX, "Lethal Enforcers (U) [!]", ".bin") == (2, 772, "MD Justifiers")
    assert emufix.pick_gun(GPGX, "T2 - The Arcade Game (U)", ".bin") == (2, 516, "MD Menacer")
    assert emufix.pick_gun(GPGX, "Safari Hunt (Europe)", ".sms") == (1, 260, "MS Light Phaser")
    assert emufix.pick_gun(PSX, "Time Crisis (USA)", ".cue") == (1, 260, "Guncon / G-Con 45")
    assert emufix.pick_gun(PSX, "Lethal Enforcers I & II (USA)", ".cue") == (1, 516, "Justifier")
    assert emufix.pick_gun([[{"desc": "RetroPad", "id": 1}]], "x", ".bin") is None


def test_pick_gun_by_name_outside_lightgun_class():
    # Mesen (POINTER) and FCEUmm (MOUSE) file their Zapper outside RETRO_DEVICE_LIGHTGUN;
    # pick_gun must still find it by name.
    assert emufix.pick_gun(MESEN, "Wild Gunman (World)", ".nes") == (1, 262, "Zapper")
    assert emufix.pick_gun(FCEUMM, "Wild Gunman (World)", ".nes") == (1, 258, "Zapper")


def test_looks_like_gun_device():
    assert emufix.looks_like_gun_device("Zapper")
    assert emufix.looks_like_gun_device("Guncon / G-Con 45")
    assert not emufix.looks_like_gun_device("Standard Controller")
    assert not emufix.looks_like_gun_device("Gamepad")


def test_apply_edit_keeps_other_lines_and_backs_up(tmp_path):
    p = tmp_path / "retroarch.cfg"
    p.write_text('video_fullscreen = "true"\ninput_player2_mouse_index = "1"\n')
    emufix.apply_edit(emufix.Edit(p, {"input_player2_mouse_index": "0", "input_player2_gun_start": "enter"}, "cfg"))
    t = p.read_text()
    assert 'video_fullscreen = "true"' in t
    assert 'input_player2_mouse_index = "0"' in t and 'input_player2_mouse_index = "1"' not in t
    assert 'input_player2_gun_start = "enter"' in t
    (bk,) = emufix.backups(tmp_path.name)
    assert (bk / "01-retroarch.cfg").read_text().count('"1"') == 1


def test_mame_plan(tmp_path):
    (tmp_path / "mame.exe").write_bytes(b"")
    (tmp_path / "mame.ini").write_text("rompath                   roms\nlightgun                  0\n")
    plan = emufix.plan_mame(str(tmp_path / "mame.exe"))
    for e in plan.edits:
        emufix.apply_edit(e)
    t = (tmp_path / "mame.ini").read_text()
    assert "lightgun                  1" in t and "rompath" in t
    assert not emufix.plan_mame(str(tmp_path / "mame.exe")).edits


def test_retroarch_settings_in_appdata(tmp_path, monkeypatch):
    r"""Installed RetroArch: program in one folder, settings in %APPDATA%\RetroArch."""
    import sys
    from lightgun_host import emucheck, emulocate
    prog = tmp_path / "Program Files" / "RetroArch"
    prog.mkdir(parents=True)
    (prog / "retroarch.exe").write_bytes(b"")
    appdata = tmp_path / "AppData"
    (appdata / "RetroArch").mkdir(parents=True)
    (appdata / "RetroArch" / "retroarch.cfg").write_text('input_driver = "dinput"\n'
                                                         'input_remapping_directory = ":\\myremaps"\n')
    monkeypatch.setenv("APPDATA", str(appdata))
    monkeypatch.setattr(sys, "platform", "win32")
    rp = emulocate.retroarch_paths(prog / "retroarch.exe", emucheck._read_kv)
    assert rp.cfg == appdata / "RetroArch" / "retroarch.cfg"
    assert rp.remaps == prog / "myremaps"            # ':' = RetroArch's own folder
    assert rp.info == appdata / "RetroArch" / "info"  # default: next to the settings file


def test_permission_hint_program_files(monkeypatch):
    import sys
    from pathlib import Path
    monkeypatch.setattr(sys, "platform", "win32")
    h = emufix.permission_hint(Path(r"C:\Program Files\RetroArch\retroarch.cfg"))
    assert "administrator" in h and "move" in h


def test_mame_plan_creates_missing_ini(tmp_path):
    (tmp_path / "mame.exe").write_bytes(b"")
    plan = emufix.plan_mame(str(tmp_path / "mame.exe"))
    assert plan.create_mame_ini and plan.edits


@pytest.mark.skipif(sys.platform != "win32", reason=(
    "tests the Windows-only 'player 2 aims with the virtual pad's right stick' workaround "
    "(plan_mame branches on sys.platform); on Linux the x11 lightgun provider gives each "
    "phone its own real gun device instead, so no cfg edit is needed or expected there."))
def test_mame_player2_cfg(tmp_path):
    (tmp_path / "mame.exe").write_bytes(b"")
    (tmp_path / "mame.ini").write_text("lightgun 1\nlightgun_device lightgun\nlightgunprovider win32\n")
    (tmp_path / "cfg").mkdir()
    (tmp_path / "cfg" / "default.cfg").write_text(
        '<?xml version="1.0"?>\n<mameconfig version="10">\n<system name="default">\n<mixer />\n</system>\n</mameconfig>\n')
    plan = emufix.plan_mame(str(tmp_path / "mame.exe"))
    cfg_edits = [e for e in plan.edits if e.style == "mamecfg"]
    assert cfg_edits and cfg_edits[0].when == "closed"
    for e in plan.edits:
        emufix.apply_edit(e)
    t = (tmp_path / "cfg" / "default.cfg").read_text()
    assert "<mixer" in t and 'type="P2_LIGHTGUN_X"' in t and "JOYCODE_1_RXAXIS" in t
    assert not [e for e in emufix.plan_mame(str(tmp_path / "mame.exe")).edits if e.style == "mamecfg"]


def test_player2_buttons():
    from lightgun_host.presets import player_buttons
    b, off = player_buttons("mame", 1, "gamepad")
    assert b["start"] == "key:2" and b["select"] == "key:6" and b["trigger"] == "key:a"
    assert off == "offscreen:key:a"
    b, off = player_buttons("retroarch", 1, "gamepad")
    assert b["trigger"] == "pad:rt"
    b, _ = player_buttons("mame", 0, "mouse")
    assert b["start"] == "key:1"


def test_mame_phone_buttons_match_mame_defaults():
    # MAME: middle click is NOT bound in light gun mode; Button 2 = LAlt, Button 3 = Space
    from lightgun_host.presets import PRESETS, button_labels
    b = PRESETS["mame"]["buttons"]
    assert (b["trigger"], b["a"], b["b"], b["start"], b["select"]) == ("mouse:left", "key:lalt", "key:space", "key:1", "key:5")
    assert button_labels("mame", 0)["a"] == "BUTTON 2"
    assert button_labels("mame", 1)["select"] == "COIN 2"
    assert button_labels("generic", 0)["b"] == "KEY SPACE"


def test_mame_per_game_remaps_are_reset(tmp_path):
    cfg = tmp_path / "cfg"
    cfg.mkdir()
    (cfg / "lethalen.cfg").write_text(
        '<?xml version="1.0"?>\n<mameconfig version="10"><system name="lethalen"><input>'
        '<port tag=":IN1" type="P1_BUTTON2" mask="32" defvalue="32"><newseq type="standard">KEYCODE_X</newseq></port>'
        '<port tag=":DSW" type="DIPSWITCH" mask="1" defvalue="1" value="0" />'
        '</input></system></mameconfig>\n')
    found = emufix.mame_game_remaps(cfg)
    assert found == {cfg / "lethalen.cfg": ["P1_BUTTON2"]}
    e = emufix.Edit(cfg / "lethalen.cfg", {"reset to default": "P1_BUTTON2"}, "mamecfg_clear", "closed")
    emufix.apply_edit(e)
    t = (cfg / "lethalen.cfg").read_text()
    assert "KEYCODE_X" not in t and "DIPSWITCH" in t            # dip switch settings kept
    assert emufix.backups("cfg")


def test_duckstation_gun_bindings_written_into_its_settings(tmp_path):
    exe = tmp_path / "duckstation-qt-x64-ReleaseLTCG.exe"
    exe.write_bytes(b"")
    (tmp_path / "portable.txt").write_text("")
    ini = tmp_path / "settings.ini"
    ini.write_text("[Main]\nSettingsVersion = 3\n\n[Pad1]\nType = AnalogController\nUp = Keyboard/W\n")
    plan = emufix.plan_duckstation(str(exe))
    assert plan.edits and plan.edits[0].when == "closed"
    emufix.apply_edit(plan.edits[0])
    have = emufix.read_inisec(ini)
    assert have["Pad1/Type"] == "GunCon" and have["Pad1/Trigger"] == "Pointer-0/LeftButton"
    assert have["Main/SettingsVersion"] == "3"
    assert not emufix.plan_duckstation(str(exe)).edits          # nothing left to do


def test_pcsx2_section_created_when_missing(tmp_path):
    exe = tmp_path / "pcsx2-qt.exe"
    exe.write_bytes(b"")
    (tmp_path / "portable.ini").write_text("")
    (tmp_path / "inis").mkdir()
    (tmp_path / "inis" / "PCSX2.ini").write_text("[UI]\nStartFullscreen = false\n")
    plan = emufix.plan_pcsx2(str(exe))
    emufix.apply_edit(plan.edits[0])
    have = emufix.read_inisec(tmp_path / "inis" / "PCSX2.ini")
    assert have["USB1/Type"] == "guncon2" and have["UI/StartFullscreen"] == "false"
