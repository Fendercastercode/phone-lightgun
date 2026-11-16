import json
import sys

from lightgun_host import emucheck


def test_mame_flags_rawinput(tmp_path):
    exe = tmp_path / "mame.exe"
    exe.write_bytes(b"")
    (tmp_path / "mame.ini").write_text("lightgun 1\nlightgun_device lightgun\nlightgunprovider rawinput\n")
    st = emucheck.check(str(exe))
    assert st.name == "MAME"
    if sys.platform == "win32":
        assert any("lightgunprovider" in c.text for c in st.problems)


def test_mame_missing_ini(tmp_path):
    exe = tmp_path / "mame.exe"
    exe.write_bytes(b"")
    assert emucheck.check(str(exe)).problems


def test_retroarch_gun_ports(tmp_path):
    ra = tmp_path
    (ra / "retroarch.exe").write_bytes(b"")
    (ra / "retroarch.cfg").write_text('input_driver = "dinput"\ninput_libretro_device_p1 = "260"\n'
                                      'input_player2_mouse_index = "1"\ninput_player2_gun_trigger_mbtn = "1"\n')
    (ra / "info").mkdir()
    (ra / "info" / "genesis_plus_gx_libretro.info").write_text('corename = "Genesis Plus GX"\n')
    (ra / "playlists" / "builtin").mkdir(parents=True)
    (ra / "playlists" / "builtin" / "content_history.lpl").write_text(json.dumps({"items": [
        {"path": "C:\roms\Lethal Enforcers (U) [!].bin", "core_path": "C:\ra\cores\genesis_plus_gx_libretro.dll"}]}))
    rm = ra / "config" / "remaps" / "Genesis Plus GX"
    rm.mkdir(parents=True)
    (rm / "Lethal Enforcers (U) [!].rmp").write_text('input_libretro_device_p2 = "772"\n')
    st = emucheck.check(str(ra / "retroarch.exe"))
    texts = " | ".join(c.text for c in st.problems)
    assert st.game == "Lethal Enforcers (U) [!]"
    assert "all set to light guns" in texts       # port 1 phaser + port 2 justifier
    assert "port 2 mouse index = 1" in texts
    assert "port 2 gun start" in texts


def test_unknown_program():
    assert emucheck.check(r"C:\Windows\explorer.exe") is None
