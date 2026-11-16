"""Bad Wi-Fi and lost messages: stuck buttons, lost taps, lost calibration results, net stats."""
import time

from lightgun_host import protocol as P
from lightgun_host.calibration import Calibrator
from lightgun_host.inject import Injector, NullBackend
from lightgun_host.net import GunState

KEY = bytes(16)
CAL = P.FLAG_CALIBRATED | P.FLAG_ON_SCREEN | P.FLAG_TRACKING


def _inj():
    b = NullBackend()
    inj = Injector(b, b.mons[0], {"trigger": "mouse:left", "reload": "mouse:right"}, "offscreen:right")
    return inj, b


def _clicks(b):
    return [e for e in b.events if e[0] == "action"]


def test_presses_survive_the_wire():
    p = P.AimPacket(0, 1, 5, 0.5, 0.5, CAL, 0, 0x23)
    assert P.decode_aim(KEY, P.encode_aim(KEY, p)).presses == 0x23


def test_tap_lost_in_a_wifi_gap_is_replayed():
    inj, b = _inj()
    inj.update(P.AimPacket(0, 1, 0, 0.5, 0.5, CAL, 0, 0x00))
    # packets carrying the press AND the release were lost; the next one shows 1 more trigger press
    inj.update(P.AimPacket(0, 9, 0, 0.5, 0.5, CAL, 0, 0x01))
    assert ("action", 0, "mouse:left", True) in _clicks(b)
    time.sleep(0.12)
    assert _clicks(b)[-1] == ("action", 0, "mouse:left", False)   # released after one frame


def test_normal_press_is_not_doubled():
    inj, b = _inj()
    inj.update(P.AimPacket(0, 1, 0, 0.5, 0.5, CAL, 0, 0x00))
    inj.update(P.AimPacket(0, 2, 0, 0.5, 0.5, CAL, P.BTN_TRIGGER, 0x01))   # edge seen normally
    inj.update(P.AimPacket(0, 3, 0, 0.5, 0.5, CAL, 0, 0x01))
    assert [c for c in _clicks(b) if c[3]] == [("action", 0, "mouse:left", True)]


def test_presses_during_calibration_are_not_replayed():
    inj, b = _inj()
    inj.update(P.AimPacket(0, 1, 0, 0.5, 0.5, CAL, 0, 0x00))
    inj.set_enabled(False, 0)
    inj.update(P.AimPacket(0, 2, 0, 0.5, 0.5, CAL, 0, 0x05))   # five calibration shots
    inj.set_enabled(True, 0)
    inj.update(P.AimPacket(0, 3, 0, 0.5, 0.5, CAL, 0, 0x05))
    assert not _clicks(b)


def test_held_trigger_released_when_the_phone_goes_silent():
    inj, b = _inj()
    inj.update(P.AimPacket(0, 1, 0, 0.5, 0.5, CAL, P.BTN_TRIGGER, 0x01))
    assert inj.release_stale(0.3) == []          # still fresh
    inj._last_t[0] -= 1.0                        # ...then a second of silence
    assert inj.release_stale(0.3) == [0]
    assert _clicks(b)[-1] == ("action", 0, "mouse:left", False)


def test_loss_and_lateness_are_measured():
    g = GunState()
    t0 = 100.0
    for i in range(100):
        if i % 10 == 5:
            continue                             # 10% lost
        now = t0 + i * 0.005 + (0.08 if i == 50 else 0.0)   # one 80 ms spike
        g.track(i, int(i * 0.005 * 1e9), now)
        g.last_seq, g.last_rx = i, now
    assert g._lost == 10
    assert max(g._late) > 0.07


class _UI:
    def show_markers(self, *a): pass
    def show_message(self, *a, **k): pass
    def show_targets(self, *a): pass


class _Store:
    def screen_size(self, _): return (1.0, 0.56)


def test_lost_result_is_sent_again_and_silent_phone_cancels():
    sent = []
    c = Calibrator(_Store(), "d", 1920, 1080, sent.append, _UI(), lambda on: None)
    c._finish({"type": "calib_result", "flow": "Q", "ok": True}, "Locked on")
    sent.clear()
    c.handle({"type": "aruco_obs", "markers": {}})     # phone never got it and keeps scanning
    assert sent and sent[0]["type"] == "calib_result"
    # a phone that disappears mid-flow
    c.handle({"type": "calib_request", "flow": "Q"})
    assert c.s is not None
    c.last_rx -= 30
    c.tick()
    assert c.s is None and sent[-1]["type"] == "calib_error"
