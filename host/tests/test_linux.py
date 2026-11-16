"""Linux-specific behaviour, tested on any OS by faking the platform (and evdev)."""
import sys
import types

from lightgun_host import autopreset, emucheck, emufix, emulocate
from lightgun_host.inject import Monitor


def _fake_evdev(monkeypatch):
    ev = types.ModuleType("evdev")

    class E:
        def __getattr__(self, name):
            return hash(name) & 0xFFFF
    ev.ecodes = E()
    ev.AbsInfo = lambda *a: a
    ev.UInput = object
    monkeypatch.setitem(sys.modules, "evdev", ev)
    sys.modules.pop("lightgun_host.inject.linux", None)
    from lightgun_host.inject import linux
    return linux


def test_two_monitors_map_to_the_shared_absolute_device(monkeypatch):
    linux = _fake_evdev(monkeypatch)
    b = object.__new__(linux.LinuxBackend)          # skip __init__ (needs /dev/uinput)
    # left 1920x1080 at 0,0 and a 4K TV at 200% scaling to its right (logical 1920x1080)
    b._rects = {"L": (0, 0, 1920, 1080, 1.0), "TV": (1920, 0, 1920, 1080, 2.0)}
    b._desk = (0, 0, 3840, 1080)
    tv = Monitor("TV", "TV", 1920, 0, 3840, 2160)
    assert b.desktop_fraction(tv, 0.0, 0.0) == (0.5, 0.0)
    assert b.desktop_fraction(tv, 1.0, 0.5) == (1.0, 0.5)
    left = Monitor("L", "L", 0, 0, 1920, 1080)
    assert b.desktop_fraction(left, 0.5, 1.0) == (0.25, 1.0)
    # unknown monitor (single screen setups): pass through, clamped
    assert b.desktop_fraction(Monitor("?", "?", 0, 0, 1, 1), 1.4, -0.2) == (1.0, 0.0)


def test_backend_explains_missing_uinput(monkeypatch):
    linux = _fake_evdev(monkeypatch)
    monkeypatch.setattr(linux, "uinput_ok", lambda: False)
    try:
        linux.LinuxBackend()
        raise AssertionError("should refuse")
    except PermissionError as e:
        assert "start-host.sh" in str(e)


def test_mame_fix_on_linux_uses_x11_and_no_joystick_mapping(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    ini = tmp_path / ".mame" / "mame.ini"
    ini.parent.mkdir()
    ini.write_text("lightgun                  0\n")
    monkeypatch.setattr(emulocate, "mame_ini", lambda exe: ini)
    plan = emufix.plan_mame(str(tmp_path / "mame"))
    v = plan.edits[0].values
    assert v["lightgunprovider"] == "x11" and v["lightgun"] == "1" and "joystick" not in v
    assert v["lightgun_index1"] == '"Phone Lightgun 1"' and v["lightgun_index2"] == '"Phone Lightgun 2"'
    # MAME's defaults already work on Linux (player 2 = light gun 2): default.cfg isn't touched
    assert not [e for e in plan.edits if e.style == "mamecfg"]


def test_mame_check_on_wayland_warns(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setenv("WAYLAND_DISPLAY", "wayland-0")
    ini = tmp_path / "mame.ini"
    ini.write_text('lightgun 1\nlightgun_device lightgun\nlightgunprovider x11\n'
                   'lightgun_index1 "Phone Lightgun 1"\nlightgun_index2 "Phone Lightgun 2"\n')
    monkeypatch.setattr(emulocate, "mame_ini", lambda exe: ini)
    st = emucheck.check_mame(str(tmp_path / "mame"))
    bad = [c.text for c in st.checks if not c.ok]
    assert bad == ["desktop is Wayland"]


def test_auto_preset_without_xdotool_uses_the_only_running_emulator(monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(autopreset, "_foreground_linux", lambda: None)
    monkeypatch.setattr(emulocate, "running_emulators", lambda: ["/usr/bin/mame"])
    assert autopreset.foreground_exe() == "/usr/bin/mame"
    monkeypatch.setattr(emulocate, "running_emulators", lambda: ["/usr/bin/mame", "/usr/bin/retroarch"])
    assert autopreset.foreground_exe() is None
