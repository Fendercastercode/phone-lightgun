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
        {"path": r"C:\roms\Lethal Enforcers (U) [!].bin", "core_path": r"C:\ra\cores\genesis_plus_gx_libretro.dll"}]}))
    rm = ra / "config" / "remaps" / "Genesis Plus GX"
    rm.mkdir(parents=True)
    (rm / "Lethal Enforcers (U) [!].rmp").write_text('input_libretro_device_p2 = "772"\n')
    st = emucheck.check(str(ra / "retroarch.exe"))
    texts = " | ".join(c.text for c in st.problems)
    assert st.game == "Lethal Enforcers (U) [!]"
    assert "all set to light guns" in texts       # port 1 phaser + port 2 justifier
    assert "port 2 mouse index = 1" in texts
    assert "port 2 gun start" in texts


def test_retroarch_gun_port_outside_lightgun_class(tmp_path, monkeypatch):
    # Mesen's Zapper is RETRO_DEVICE_POINTER (262), not RETRO_DEVICE_LIGHTGUN; the port should
    # still be recognized once the fix has set it, via the core-probe name fallback.
    from lightgun_host import emufix
    monkeypatch.setattr(emufix, "core_ports",
                         lambda dll, game=None: [[{"desc": "Standard Controller", "id": 257},
                                                    {"desc": "Zapper", "id": 262}]] * 2)
    ra = tmp_path
    (ra / "retroarch.exe").write_bytes(b"")
    (ra / "retroarch.cfg").write_text('input_driver = "dinput"\ninput_libretro_device_p1 = "262"\n'
                                      'input_player1_mouse_index = "0"\ninput_player1_gun_trigger_mbtn = "1"\n'
                                      'input_player1_gun_start_mbtn = "4"\n')
    (ra / "info").mkdir()
    (ra / "info" / "mesen_libretro.info").write_text('corename = "Mesen"\n')
    (ra / "playlists" / "builtin").mkdir(parents=True)
    (ra / "playlists" / "builtin" / "content_history.lpl").write_text(json.dumps({"items": [
        {"path": r"C:\roms\Wild Gunman (World).nes", "core_path": r"C:\ra\cores\mesen_libretro.dll"}]}))
    st = emucheck.check(str(ra / "retroarch.exe"))
    assert st.game == "Wild Gunman (World)"
    texts = " | ".join(c.text for c in st.checks)
    assert "port 1 is a light gun" in texts


def test_unknown_program():
    assert emucheck.check(r"C:\Windows\explorer.exe") is None


def _ra_with_history(ra, port):
    (ra / "retroarch.exe").write_bytes(b"")
    (ra / "retroarch.cfg").write_text(f'input_driver = "dinput"\nnetwork_cmd_enable = "true"\n'
                                      f'network_cmd_port = "{port}"\n')
    (ra / "info").mkdir()
    (ra / "info" / "genesis_plus_gx_libretro.info").write_text('corename = "Genesis Plus GX"\n')
    (ra / "playlists" / "builtin").mkdir(parents=True)
    (ra / "playlists" / "builtin" / "content_history.lpl").write_text(json.dumps({"items": [
        {"path": r"C:\roms\Old Game.bin", "core_path": r"C:\ra\cores\genesis_plus_gx_libretro.dll"},
        {"path": r"C:\roms\Lethal Enforcers.bin", "core_path": r"C:\ra\cores\genesis_plus_gx_libretro.dll"}]}))


def _fake_retroarch(reply: bytes):
    """A one-shot RetroArch network command port answering GET_STATUS with [reply]."""
    import socket
    import threading
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    s.settimeout(2)

    def serve():
        try:
            data, addr = s.recvfrom(64)
            if data.startswith(b"GET_STATUS"):
                s.sendto(reply, addr)
        except OSError:
            pass
        finally:
            s.close()
    threading.Thread(target=serve, daemon=True).start()
    return s.getsockname()[1]


def test_retroarch_follows_the_running_game_not_the_history(tmp_path):
    emucheck._status_cache.clear()
    port = _fake_retroarch(b"GET_STATUS PLAYING Genesis Plus GX,Lethal Enforcers,crc32=1234abcd\n")
    _ra_with_history(tmp_path, port)
    st = emucheck.check(str(tmp_path / "retroarch.exe"))
    assert st.game == "Lethal Enforcers"     # not "Old Game", the newest history entry


def test_retroarch_menu_without_a_game(tmp_path):
    emucheck._status_cache.clear()
    port = _fake_retroarch(b"GET_STATUS CONTENTLESS\n")
    _ra_with_history(tmp_path, port)
    st = emucheck.check(str(tmp_path / "retroarch.exe"))
    assert st.game == ""
    assert any("no game running" in c.text for c in st.problems)


def test_retroarch_closed_does_not_show_the_last_game(tmp_path):
    import socket
    emucheck._status_cache.clear()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()                                 # nothing listening there now
    _ra_with_history(tmp_path, port)
    st = emucheck.check(str(tmp_path / "retroarch.exe"))
    assert st.game == ""
    assert any("isn't running a game" in c.text for c in st.problems)
