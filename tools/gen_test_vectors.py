"""Regenerate shared/test_vectors.json (consumed by host and Android unit tests).

    host/.venv/Scripts/python tools/gen_test_vectors.py
"""
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "host"))

from lightgun_host import protocol as P  # noqa: E402
from lightgun_host.geometry import Barrel, aim_point, mat_to_quat, rotvec_to_mat  # noqa: E402
from lightgun_host.sim import default_screen  # noqa: E402


def main():
    rng = np.random.default_rng(1234)
    key = bytes(range(16))
    aim = []
    for i in range(4):
        f = {"gun_id": i, "seq": int(rng.integers(0, 2**32)), "phone_time_ns": int(rng.integers(0, 2**62)),
             "x": float(np.float32(rng.random())), "y": float(np.float32(rng.random())),
             "flags": int(rng.integers(0, 16)), "buttons": int(rng.integers(0, 128))}
        aim.append({"fields": f, "hex": P.encode_aim(key, P.AimPacket(**f)).hex()})
    ctrl_msg = {"type": "hello", "device": "test"}
    ctrl = {"gun_id": 1, "seq": 5, "json": json.dumps(ctrl_msg, separators=(",", ":")),
            "hex": P.encode_ctrl(key, 1, 5, ctrl_msg).hex()}

    scr = default_screen()
    barrel = Barrel(np.array([0.0, 1.0, 0.0]), np.array([0.02, -0.04, 0.01]))
    rays = []
    for _ in range(8):
        R = rotvec_to_mat(rng.normal(0, 1.0, 3))
        p = rng.normal(0, 0.2, 3)
        res = aim_point(scr, barrel, R, p)
        rays.append({"q": mat_to_quat(R).tolist(), "p": p.tolist(),
                     "hit": None if res is None else {"x": res[0], "y": res[1], "on": res[2]}})
    # a few that certainly hit
    from lightgun_host.sim import pose_aiming_at
    for x, y in [(0.5, 0.5), (0.1, 0.9), (1.2, 0.3)]:
        R = pose_aiming_at(barrel, np.zeros(3), scr.point(x, y), 0.7)
        res = aim_point(scr, barrel, R, np.zeros(3))
        rays.append({"q": mat_to_quat(R).tolist(), "p": [0.0, 0.0, 0.0],
                     "hit": {"x": res[0], "y": res[1], "on": res[2]}})
    out = {"key": key.hex(), "aim_packets": aim, "ctrl_packet": ctrl,
           "screen": scr.to_dict(), "barrel": barrel.to_dict(), "rays": rays}
    path = ROOT / "shared" / "test_vectors.json"
    path.write_text(json.dumps(out, indent=1))
    print("wrote", path)


if __name__ == "__main__":
    main()
