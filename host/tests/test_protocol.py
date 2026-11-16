import json
import socket
import time
from pathlib import Path

import numpy as np

from lightgun_host import protocol as P
from lightgun_host.calibration import Calibrator, TARGETS_C
from lightgun_host.geometry import Barrel
from lightgun_host.inject import Injector, NullBackend
from lightgun_host.net import Receiver
from lightgun_host.sim import default_screen, make_shot_msgs
from lightgun_host.store import Store

KEY = bytes(range(16))
VECTORS = Path(__file__).resolve().parents[2] / "shared" / "test_vectors.json"


def test_aim_roundtrip_and_size():
    p = P.AimPacket(3, 0xFFFFFFFF, 123456789012345, 0.25, 0.75,
                    P.FLAG_ON_SCREEN | P.FLAG_CALIBRATED, P.BTN_TRIGGER | P.BTN_START)
    raw = P.encode_aim(KEY, p)
    assert len(raw) == 32
    q = P.decode_aim(KEY, raw)
    assert q == p
    bad = bytearray(raw)
    bad[17] ^= 1
    assert P.decode_aim(KEY, bytes(bad)) is None
    assert P.decode_aim(b"x" * 16, raw) is None


def test_ctrl_roundtrip():
    raw = P.encode_ctrl(KEY, 1, 7, {"type": "hello", "device": "pixel"})
    assert P.decode_ctrl(KEY, raw) == (1, 7, {"type": "hello", "device": "pixel"})
    assert P.decode_ctrl(KEY, raw[:-1] + b"\0") is None


def test_seq_wrap():
    assert P.seq_newer(0, 0xFFFFFFFF)
    assert not P.seq_newer(5, 5)
    assert not P.seq_newer(4, 5)


def test_shared_vectors():
    v = json.loads(VECTORS.read_text())
    key = bytes.fromhex(v["key"])
    for case in v["aim_packets"]:
        pkt = P.AimPacket(**case["fields"])
        assert P.encode_aim(key, pkt).hex() == case["hex"]


def test_injector_offscreen_trigger_and_release():
    be = NullBackend()
    inj = Injector(be, be.monitors()[0], {"trigger": "mouse:left", "reload": "mouse:right"}, "mouse:right")
    f = P.FLAG_CALIBRATED
    inj.update(P.AimPacket(0, 1, 0, 0.5, 0.5, f | P.FLAG_ON_SCREEN, P.BTN_TRIGGER))
    inj.update(P.AimPacket(0, 2, 0, 0.5, 0.5, f | P.FLAG_ON_SCREEN, 0))
    inj.update(P.AimPacket(0, 3, 0, 1.0, 0.5, f, P.BTN_TRIGGER))  # off-screen
    inj.update(P.AimPacket(0, 4, 0, 0.5, 0.5, f | P.FLAG_ON_SCREEN, 0))  # released after coming back
    acts = [e[2:] for e in be.events if e[0] == "action"]
    assert acts == [("mouse:left", True), ("mouse:left", False), ("mouse:right", True), ("mouse:right", False)]
    be.events.clear()
    inj.update(P.AimPacket(0, 5, 0, 0.5, 0.5, 0, 0))  # uncalibrated: no move
    assert not be.events


def test_receiver_end_to_end(tmp_path):
    got, ctrl = [], []
    rx = Receiver(KEY, 0, got.append, lambda g, m, a: ctrl.append(m), bind="127.0.0.1").start()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1)
        dst = ("127.0.0.1", rx.port)
        for seq in (1, 2, 2, 1, 3):  # duplicates / reorder dropped
            s.sendto(P.encode_aim(KEY, P.AimPacket(0, seq, 0, 0.1, 0.2, 0, 0)), dst)
        s.sendto(P.encode_aim(b"k" * 16, P.AimPacket(0, 9, 0, 0, 0, 0, 0)), dst)
        s.sendto(P.encode_ctrl(KEY, 0, 1, {"type": "ping", "t": 42}), dst)
        pong = P.decode_ctrl(KEY, s.recvfrom(9000)[0])
        time.sleep(0.1)
        assert [p.seq for p in got] == [1, 2, 3]
        assert rx.bad_mac == 1 and rx.dropped == 2
        assert pong[2]["type"] == "pong" and pong[2]["t"] == 42 and "loss" in pong[2]["net"]
        assert ctrl and ctrl[0]["type"] == "ping"
    finally:
        rx.stop()


class FakeUI:
    def __init__(self):
        self.calls = []

    def show_targets(self, *a):
        self.calls.append(("targets", a))

    def show_markers(self, *a):
        self.calls.append(("markers", a))

    def show_message(self, *a, **k):
        self.calls.append(("message", a))

    def hide(self):
        pass


def test_calibrator_flow_c(tmp_path):
    store = Store(tmp_path / "c.json")
    scr = default_screen()
    store.set_screen_size("d", scr.width_m, scr.height_m, "test")
    sent = []
    cal = Calibrator(store, "d", 1920, 1080, sent.append, FakeUI(), lambda on: None)
    barrel = Barrel(np.array([0, 0, -1.0]))
    cal.handle({"type": "calib_request", "flow": "C", "barrel": barrel.to_dict(), "ar_session": "s1"})
    assert sent[-1]["type"] == "calib_target" and sent[-1]["index"] == 0
    rng = np.random.default_rng(0)
    shots = make_shot_msgs(scr, barrel, TARGETS_C, [np.zeros(3)] * 4, rng)
    cal.handle({"type": "shot", "index": 2, **shots[0]})  # wrong index -> re-sent target
    assert sent[-1]["retry"] is True and sent[-1]["index"] == 0
    for i, s in enumerate(shots):
        cal.handle({"type": "shot", "index": i, **s})
    res = sent[-1]
    assert res["type"] == "calib_result" and res["ok"] and res["rms_px"] < 0.01
    assert np.allclose(res["screen"]["s0"], scr.s0, atol=1e-4)
