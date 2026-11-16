"""Reading the emulator's real bindings, and backing up before every save."""
import json

from lightgun_host import bindread, emufix

CFG = ('<?xml version="1.0"?>\n<mameconfig version="10"><system name="default"><input>{}</input></system>'
       '</mameconfig>\n')


def _port(t, seq, tag=""):
    tag = f' tag="{tag}" mask="1" defvalue="1"' if tag else ""
    return f'<port{tag} type="{t}"><newseq type="standard">{seq}</newseq></port>'


def _mame(tmp_path, default_cfg="", games=None):
    (tmp_path / "mame.exe").write_bytes(b"")
    (tmp_path / "mame.ini").write_text("lightgun 1\nlightgun_device lightgun\nlightgunprovider win32\n")
    (tmp_path / "cfg").mkdir(exist_ok=True)
    if default_cfg:
        (tmp_path / "cfg" / "default.cfg").write_text(CFG.format(default_cfg))
    for name, body in (games or {}).items():
        (tmp_path / "cfg" / f"{name}.cfg").write_text(CFG.replace('"default"', f'"{name}"').format(body))
    return str(tmp_path / "mame.exe")


def test_parse_mame_source_defaults():
    src = ('\t\tINPUT_PORT_DIGITAL_TYPE(  1, PLAYER1,  BUTTON2,  N_p("input-name", "%p Button 2"),  '
           'input_seq(KEYCODE_LALT, input_seq::or_code, MOUSECODE_BUTTON3_INDEXED(0), input_seq::or_code, '
           'GUNCODE_BUTTON2_INDEXED(0)) ) \\\n'
           '\t\tINPUT_PORT_DIGITAL_TYPE(  0, OTHER,    COIN1,    N_p("input-name", "Coin 1"),  '
           'input_seq(KEYCODE_5, input_seq::or_code, JOYCODE_SELECT_INDEXED(0)) ) \\\n'
           '\t\tINPUT_PORT_DIGITAL_TYPE(  0, OTHER,    COIN12,   N_p("input-name", "Coin 12"),  input_seq() ) \\\n')
    d = bindread.parse_inpttype(src)
    assert d["P1_BUTTON2"] == bindread.MAME_BUILTIN["P1_BUTTON2"]
    assert d["COIN1"] == "KEYCODE_5 OR JOYCODE_1_SELECT" and d["COIN12"] == "NONE"


def test_mame_defaults_follow_the_gun_buttons():
    seq = bindread.MAME_BUILTIN
    assert bindread.mame_usable(seq["P1_BUTTON1"], 1, False, False) == "mouse:left"
    assert bindread.mame_usable(seq["P1_BUTTON2"], 1, False, False) == "mouse:right"   # gun button 2
    assert bindread.mame_usable(seq["P1_BUTTON3"], 1, False, False) == "key:space"     # no gun code
    assert bindread.mame_usable(seq["P2_BUTTON1"], 2, False, False) == "key:a"         # Windows: one cursor
    assert bindread.mame_usable(seq["P2_BUTTON1"], 2, True, False) == "mouse:left"     # Linux: its own gun
    assert bindread.mame_usable("KEYCODE_LSHIFT KEYCODE_A", 1, False, False) is None  # chord
    assert bindread.mame_usable("NONE", 1, False, False) is None


def test_user_and_per_game_remaps_are_followed(tmp_path):
    exe = _mame(tmp_path, _port("COIN1", "KEYCODE_C") + _port("START1", "KEYCODE_ENTER"),
                {"area51": _port("P1_BUTTON3", "KEYCODE_X", ":IN1")})
    live = bindread.read_mame(exe, "MAME: Area 51 (R3000) [area51]")
    assert live.buttons["select"] == "key:c" and live.buttons["start"] == "key:enter"
    assert live.buttons["b"] == "key:x" and live.labels["b"] == "BUTTON 3"
    assert live.buttons["reload"] == "offscreen:left" and not live.unbound
    assert "cfg/area51.cfg" in live.sources
    # another game without its own mapping uses default.cfg
    assert bindread.read_mame(exe, "MAME: Time Crisis [timecris]").buttons["b"] == "key:space"


def test_fix_keeps_user_bindings_and_only_adds_what_is_missing(tmp_path):
    exe = _mame(tmp_path, _port("COIN1", "JOYCODE_1_BUTTON7") + _port("START1", "KEYCODE_ENTER")
                + _port("P2_LIGHTGUN_X", "JOYCODE_1_RXAXIS") + _port("P2_LIGHTGUN_Y", "JOYCODE_1_RYAXIS"),
                {"lethalen": _port("P1_BUTTON2", "JOYCODE_1_BUTTON2", ":IN1")
                 + '<port tag=":DSW" type="DIPSWITCH" mask="1" defvalue="1" value="0" />'})
    live = bindread.read_mame(exe, "MAME: Lethal Enforcers [lethalen]")
    assert set(live.unbound) == {"COIN1", "P1_BUTTON2"} and live.labels["select"] == "COIN 1 (NOT SET)"
    plan = emufix.plan_mame(exe)
    by_file = {e.path.name: e.values for e in plan.edits if e.style == "mamecfg"}
    assert by_file["default.cfg"] == {"COIN1": "JOYCODE_1_BUTTON7 OR KEYCODE_5"}   # Start (Enter) left alone
    assert by_file["lethalen.cfg"] == {"P1_BUTTON2": "JOYCODE_1_BUTTON2 OR KEYCODE_LALT"}
    emufix.apply(plan, running=False)
    t = (tmp_path / "cfg" / "lethalen.cfg").read_text()
    assert "JOYCODE_1_BUTTON2 OR KEYCODE_LALT" in t and "DIPSWITCH" in t and 'tag=":IN1"' in t
    live = bindread.read_mame(exe, "MAME: Lethal Enforcers [lethalen]")
    assert not live.unbound and live.buttons["select"] == "key:5" and live.buttons["a"] == "key:lalt"
    assert not [e for e in emufix.plan_mame(exe).edits if e.style == "mamecfg"]


def test_every_save_is_backed_up_and_undo_restores(tmp_path):
    p = tmp_path / "retroarch.cfg"
    p.write_text('input_player1_gun_aux_b = "nul"\n')
    new = tmp_path / "remaps" / "x.rmp"
    plan = emufix.FixPlan("RetroArch", "x")
    plan.edits = [emufix.Edit(p, {"input_player1_gun_aux_b": "space"}, "cfg"),
                  emufix.Edit(new, {"input_libretro_device_p1": "260"}, "cfg")]
    emufix.apply(plan, running=False)
    sets = emufix.backups("RetroArch")
    assert len(sets) == 1
    m = json.loads((sets[0] / "manifest.json").read_text())
    assert [f["copy"] is None for f in m["files"]] == [False, True]    # the .rmp was new
    assert '"nul"' in (sets[0] / m["files"][0]["copy"]).read_text()
    emufix.apply(emufix.FixPlan("RetroArch", "x", [emufix.Edit(p, {"video_vsync": "true"}, "cfg")]), False)
    assert len(emufix.backups("RetroArch")) == 2                        # a new backup every save
    emufix.restore(emufix.backups("RetroArch")[0])                      # undo the second save
    assert "video_vsync" not in p.read_text() and '"space"' in p.read_text()
    emufix.restore(emufix.backups("RetroArch")[1])                      # ...and the first one
    assert '"nul"' in p.read_text() and not new.exists()


def test_old_backups_are_pruned(tmp_path, monkeypatch):
    monkeypatch.setattr(emufix, "BACKUP_KEEP", 3)
    p = tmp_path / "a.cfg"
    for i in range(5):
        b = emufix.Backup("MAME")
        b.dir = b.dir.with_name(f"2026010{i}-000000")
        p.write_text(str(i))
        b.save(p)
    assert [d.name for d in emufix.backups("MAME")] == ["20260104-000000", "20260103-000000", "20260102-000000"]


def test_retroarch_reads_the_gun_port_binds(tmp_path):
    cfg = {"input_player1_gun_trigger_mbtn": "1", "input_player1_gun_aux_a": "x", "input_player1_gun_aux_a_mbtn": "nul",
           "input_player1_gun_start": "num1", "input_player1_gun_select": "nul", "input_player1_gun_select_mbtn": "nul"}
    assert bindread.ra_bind(cfg, 1, "gun_trigger")[0] == "mouse:left"
    assert bindread.ra_bind(cfg, 1, "gun_offscreen_shot")[0] == "mouse:right"   # RetroArch default
    assert bindread.ra_bind(cfg, 1, "gun_aux_a")[0] == "key:x"
    assert bindread.ra_bind(cfg, 1, "gun_start")[0] == "key:1"
    assert bindread.ra_bind(cfg, 1, "gun_select")[0] is None


def test_duckstation_reads_and_fix_keeps_existing(tmp_path):
    exe = tmp_path / "duckstation-qt.exe"
    exe.write_bytes(b"")
    (tmp_path / "portable.txt").write_text("")
    ini = tmp_path / "settings.ini"
    ini.write_text("[Pad1]\nType = AnalogController\n[Pad2]\nType = GunCon\nPointer = Pointer-0\n"
                   "Trigger = Keyboard/Space\nTrigger = Pointer-0/LeftButton\nA = Keyboard/Z\n")
    live = bindread.read_duckstation(str(exe))
    assert live.buttons["trigger"] == "mouse:left" and live.buttons["a"] == "key:z"
    assert set(live.unbound) == {"ShootOffscreen", "B"}
    plan = emufix.plan_duckstation(str(exe))
    assert plan.edits[0].values == {"Pad2/ShootOffscreen": "Keyboard/R", "Pad2/B": "Pointer-0/MiddleButton"}


def test_dolphin_expressions():
    assert bindread.dolphin_action("`Click 1`") == "mouse:right"
    assert bindread.dolphin_action("`Q` | `Click 0`") == "mouse:left"
    assert bindread.dolphin_action("RETURN") == "key:enter"
    assert bindread.dolphin_action("`Shift_L` & `A`") is None


def test_supermodel_reads_and_fix_adds_mouse(tmp_path):
    exe = tmp_path / "Supermodel.exe"
    exe.write_bytes(b"")
    (tmp_path / "Config").mkdir()
    ini = tmp_path / "Config" / "Supermodel.ini"
    ini.write_text('[ Global ]\nInputStart1 = "KEY_1,JOY1_BUTTON9"\nInputCoin1 = "KEY_3"\n'
                   'InputTrigger = "JOY1_BUTTON1"\nInputGunX = "JOY1_XAXIS"\nInputGunY = "MOUSE_YAXIS"\n'
                   '\n[ lostwsga ]\nInputSystem = "rawinput"\n')
    live = bindread.read_supermodel(str(exe))
    assert live.buttons["start"] == "key:1" and live.buttons["select"] == "key:3"
    assert "Trigger" in live.unbound and "GunX (aim)" in live.unbound and "GunY (aim)" not in live.unbound
    plan = emufix.plan_supermodel(str(exe))
    v = plan.edits[0].values
    assert v["Global/InputTrigger"] == '"JOY1_BUTTON1,MOUSE_LEFT_BUTTON"'
    assert v["Global/InputGunX"] == '"JOY1_XAXIS,MOUSE_XAXIS"' and "Global/InputGunY" not in v
    assert "Global/InputStart1" not in v                       # already a key: kept as it is
    emufix.apply(plan, running=False)
    t = ini.read_text()
    assert t.count("[ Global ]") == 1 and "[ lostwsga ]" in t and 'InputSystem = "rawinput"' in t
    live = bindread.read_supermodel(str(exe))
    assert live.buttons["trigger"] == "mouse:left" and live.buttons["reload"] == "mouse:right" and not live.unbound
    assert not emufix.plan_supermodel(str(exe)).edits


def test_model2_emulator_is_left_alone(tmp_path):
    exe = tmp_path / "emulator_multicpu.exe"
    exe.write_bytes(b"")
    assert bindread.read_supermodel(str(exe)) is None and not emufix.plan_supermodel(str(exe)).edits


def test_flycast_defaults_and_mapping_files(tmp_path):
    exe = tmp_path / "flycast.exe"
    exe.write_bytes(b"")
    (tmp_path / "emu.cfg").write_text("[config]\n")
    live = bindread.read_flycast(str(exe))
    assert live.buttons["trigger"] == "mouse:left" and live.buttons["select"] == "key:a"   # Coin = A key
    assert live.buttons["b"] == "none"                         # never Space: that's fast-forward
    (tmp_path / "mappings").mkdir()
    (tmp_path / "mappings" / "SDL_Keyboard_arcade.cfg").write_text(
        "[digital]\nbind0 = 40:btn_start\nbind1 = 34:btn_d\n\n[emulator]\nversion = 3\n")
    live = bindread.read_flycast(str(exe))
    assert live.buttons["select"] == "key:5" and live.buttons["start"] == "mouse:middle"   # mouse default wins


def test_dolphin_fix_only_edits_an_existing_profile(tmp_path, monkeypatch):
    exe = tmp_path / "Dolphin.exe"
    exe.write_bytes(b"")
    (tmp_path / "portable.txt").write_text("")
    assert not emufix.plan_dolphin(str(exe)).edits             # no file = Dolphin defaults: fine
    cfg = tmp_path / "User" / "Config"
    cfg.mkdir(parents=True)
    (cfg / "WiimoteNew.ini").write_text("[Wiimote1]\nDevice = DInput/0/Keyboard Mouse\nButtons/A = `Click 0`\n"
                                        "Buttons/B = `JOY_3`\nIR/Up = `Cursor Y-`\n")
    v = emufix.plan_dolphin(str(exe)).edits[0].values
    assert v["Wiimote1/Buttons/B"] == "`JOY_3` | `Click 1`" and "Wiimote1/Buttons/A" not in v
    assert "Wiimote1/IR/Down" in v and "Wiimote1/IR/Up" not in v and "Wiimote1/Device" not in v


def test_only_gun_games_are_set_up_automatically_in_retroarch():
    assert emufix.looks_like_gun_game("Lethal Enforcers II - Gun Fighters (USA) [!]")
    assert emufix.looks_like_gun_game("Time Crisis (USA)")
    assert not emufix.looks_like_gun_game("Sonic The Hedgehog (USA, Europe)")
    assert not emufix.looks_like_gun_game("Gunstar Heroes (USA)")


def test_unpressable_buttons_show_up_as_a_problem(tmp_path):
    from lightgun_host import emucheck
    exe = tmp_path / "Supermodel.exe"
    exe.write_bytes(b"")
    (tmp_path / "Config").mkdir()
    (tmp_path / "Config" / "Supermodel.ini").write_text('[ Global ]\nInputCoin1 = "NONE"\n')
    st = emucheck.check(str(exe))
    assert st.name == "Supermodel" and any("Coin1" in c.text for c in st.problems)


def test_retroarch_one_key_one_action():
    # found on a real setup: Right Shift was P1 Select AND P2 Gun Select -> 2 coins per press in the MAME
    # core; Space (the phone's B) was also RetroArch's fast-forward toggle
    cfg = {"input_player1_gun_trigger_mbtn": "1", "input_player1_gun_offscreen_shot_mbtn": "2",
           "input_player1_gun_aux_a_mbtn": "3", "input_player1_gun_aux_b": "space",
           "input_player1_gun_start": "enter", "input_player1_gun_select": "rshift",
           "input_player1_start": "enter", "input_player1_select": "rshift",
           "input_player2_gun_start": "enter", "input_player2_gun_select": "rshift",
           "input_toggle_fast_forward": "space", "input_player1_a": "x"}
    clashes = {(c, o) for _, c, o in bindread.ra_conflicts(cfg, 1, mame_core=False)}
    assert ("input_player1_gun_aux_b", "input_toggle_fast_forward") in clashes
    assert ("input_player1_gun_select", "input_player2_gun_select") in clashes
    assert ("input_player1_gun_select", "input_player1_select") not in clashes   # gun port ignores RetroPad
    fixed = emufix._ra_resolve(cfg, {}, 1, mame_core=False)
    assert fixed["input_player2_gun_select"] == "nul" and fixed["input_player2_gun_start"] == "nul"
    assert fixed["input_player1_gun_aux_b"] not in ("space", "x", "enter", "rshift")   # hotkey untouched
    assert "input_toggle_fast_forward" not in fixed
    # MAME core: Start/Coin come from the RetroPad, so the gun's copies must go
    fixed = emufix._ra_resolve(cfg, {}, 1, mame_core=True)
    assert fixed["input_player1_gun_select"] == "nul" and fixed["input_player1_gun_start"] == "nul"
    assert not bindread.ra_conflicts({**cfg, **fixed}, 1, True)


def test_mame_core_detection():
    assert emufix.is_mame_core("MAME") and not emufix.is_mame_core("MAME 2003-Plus")


def test_mesen_reads_zapper_and_defaults(tmp_path):
    import json
    from lightgun_host import bindread, autopreset
    exe = tmp_path / "Mesen.exe"
    exe.write_text("")
    (tmp_path / "settings.json").write_text(json.dumps(
        {"Nes": {"Port1": {"Type": "NesController"}, "Port2": {"Type": "NesZapper", "Mapping1": {"ZapperButtons": [513, 0]}}}}))
    live = bindread.read_mesen(str(exe))
    assert live.buttons["trigger"] == "mouse:right" and live.buttons["reload"] == "offscreen:left"
    assert not live.unbound and "NES Zapper" in live.sources[0]
    (tmp_path / "settings.json").write_text(json.dumps({"Snes": {"Port2": {"Type": "SuperScope"}}}))
    live = bindread.read_mesen(str(exe))
    assert live.buttons["trigger"] == "mouse:left" and live.buttons["a"] == "mouse:right"
    (tmp_path / "settings.json").write_text("{}")
    assert bindread.read_mesen(str(exe)).unbound
    assert autopreset.preset_for(str(exe)) == "mesen"
