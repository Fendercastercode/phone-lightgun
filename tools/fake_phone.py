"""Simulated phone: exercises the host end-to-end without hardware.

Runs a calibration flow against a synthetic screen + barrel (with realistic noise),
then streams aim packets tracing a circle using the screen pose the host solved.

    python tools/fake_phone.py --uri "lightgun://127.0.0.1:47800?k=...&id=0" --flow C --stream 5
    python tools/fake_phone.py --flow A          # key/port read from the host config file
"""
import argparse
import json
import math
import socket
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "host"))

from lightgun_host import protocol as P  # noqa: E402
from lightgun_host.calibration import marker_layout  # noqa: E402
from lightgun_host.geometry import Barrel, Screen, aim_point  # noqa: E402
from lightgun_host.sim import aruco_obs, default_screen, make_shot_msgs, pose_aiming_at  # noqa: E402
from lightgun_host.store import Store  # noqa: E402


class FakePhone:
    def __init__(self, host, port, key, gun_id, noise_deg):
        self.addr = (host, port)
        self.key, self.gun = key, gun_id
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.settimeout(3.0)
        self.ctrl_seq = 0
        self.aim_seq = 0
        self.rng = np.random.default_rng(0)
        self.noise = math.radians(noise_deg)
        self.true_screen = default_screen(width=1.21)
        self.barrel = Barrel(np.array([0.0, 1.0, 0.0]), np.array([0.02, -0.04, 0.01]))
        self.session = uuid.uuid4().hex

    def send(self, msg):
        self.ctrl_seq += 1
        self.sock.sendto(P.encode_ctrl(self.key, self.gun, self.ctrl_seq, msg), self.addr)

    def recv(self, want=None, timeout=3.0):
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, _ = self.sock.recvfrom(9000)
            except socket.timeout:
                break
            dec = P.decode_ctrl(self.key, data)
            if dec and (want is None or dec[2]["type"] in want):
                return dec[2]
        raise TimeoutError(f"no {want} from host")

    def hello(self):
        self.send({"type": "hello", "device": "fake-phone", "app_version": "sim"})
        ack = self.recv({"hello_ack"})
        d = ack["display"]
        print(f"host display {d['w_px']}x{d['h_px']}, stored size {ack['screen_size_m']}")
        return ack

    def run_flow(self, flow):
        self.send({"type": "calib_request", "flow": flow, "barrel": self.barrel.to_dict(),
                   "fit_t": False, "ar_session": self.session})
        if flow == "A":
            return self._flow_a()
        stand = [np.zeros(3), np.array([1.1, 0.02, 0.15])]
        while True:
            m = self.recv({"calib_target", "calib_result", "calib_error"}, timeout=10)
            if m["type"] != "calib_target":
                return m
            pos = stand[1] if (flow == "D" and m["index"] >= 9) else stand[0]
            shot = make_shot_msgs(self.true_screen, self.barrel, [(m["x"], m["y"])], [pos], self.rng,
                                  ang_noise=self.noise, pos_noise=0.003)[0]
            print(f"  target {m['index'] + 1}/{m['total']} at ({m['x']:.2f},{m['y']:.2f}) -> shot")
            time.sleep(0.05)
            self.send({"type": "shot", "index": m["index"], "n": 30, "w_rms": 0.02, **shot})

    def _flow_a(self):
        ack = self.hello()
        w, h = ack["display"]["w_px"], ack["display"]["h_px"]
        corner_px = {(mk.id, k): c for mk in marker_layout(w, h) for k, c in enumerate(mk.corners())}
        for i in range(120):
            eye = np.array([self.rng.uniform(-0.4, 0.4), self.rng.uniform(0.0, 0.5), self.rng.uniform(-0.9, 0.0)])
            msg = aruco_obs(self.true_screen, corner_px, w, eye,
                            self.true_screen.center + self.rng.normal(0, 0.05, 3), px_noise=0.4, rng=self.rng)
            self.send(msg)
            time.sleep(0.03)
            try:
                m = self.recv({"calib_result", "calib_error"}, timeout=0.01)
                return m
            except TimeoutError:
                pass
        return self.recv({"calib_result", "calib_error"}, timeout=5)

    def stream(self, screen: Screen, seconds, rate=200):
        t0 = time.time()
        n = 0
        while time.time() - t0 < seconds:
            t = time.time() - t0
            x, y = 0.5 + 0.3 * math.cos(t * 1.5), 0.5 + 0.3 * math.sin(t * 1.5)
            R = pose_aiming_at(self.barrel, np.zeros(3), self.true_screen.point(x, y), 0.3)
            res = aim_point(screen, self.barrel, R, np.zeros(3))
            flags = P.FLAG_TRACKING | P.FLAG_CALIBRATED
            ax, ay = 0.5, 0.5
            if res:
                ax, ay, on = res
                flags |= P.FLAG_ON_SCREEN if on else 0
            buttons = P.BTN_TRIGGER if int(t * 2) % 4 == 3 else 0
            self.aim_seq += 1
            pkt = P.AimPacket(self.gun, self.aim_seq, time.monotonic_ns(), float(np.clip(ax, 0, 1)),
                              float(np.clip(ay, 0, 1)), flags, buttons)
            self.sock.sendto(P.encode_aim(self.key, pkt), self.addr)
            n += 1
            if n % rate == 0:
                err = math.hypot(ax - x, ay - y)
                print(f"  t={t:4.1f}s true=({x:.3f},{y:.3f}) sent=({ax:.3f},{ay:.3f}) err={err * 100:.3f}% ")
            time.sleep(1 / rate)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--uri", help="pairing URI; default: read key/port from the host config")
    ap.add_argument("--flow", default="C", choices=list("ABCD"))
    ap.add_argument("--noise-deg", type=float, default=0.05, help="per-shot aim noise (deg)")
    ap.add_argument("--stream", type=float, default=5.0, help="seconds of aim streaming afterwards")
    a = ap.parse_args()
    if a.uri:
        u = urlparse(a.uri)
        q = parse_qs(u.query)
        host, port, key, gid = u.hostname, u.port, bytes.fromhex(q["k"][0]), int(q.get("id", ["0"])[0])
    else:
        st = Store()
        host, port, key, gid = "127.0.0.1", st.data["port"], st.key, 0
    ph = FakePhone(host, port, key, gid, a.noise_deg)
    ph.hello()
    res = ph.run_flow(a.flow)
    print("result:", json.dumps({k: v for k, v in res.items() if k != "screen"}))
    if res.get("screen"):
        scr = Screen.from_dict(res["screen"])
        ts = ph.true_screen
        print(f"solved width {scr.width_m:.4f} m (true {ts.width_m:.4f}), "
              f"centre error {np.linalg.norm(scr.center - ts.center) * 1000:.1f} mm")
        if a.stream > 0:
            ph.stream(scr, a.stream)


if __name__ == "__main__":
    main()
