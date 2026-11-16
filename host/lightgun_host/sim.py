"""Synthetic scenes for tests, test vectors and tools/fake_phone.py."""
from __future__ import annotations

import numpy as np

from .geometry import Barrel, Screen, mat_to_quat, normalize, rotvec_to_mat


def rot_between(a, b):
    a, b = normalize(a), normalize(b)
    v = np.cross(a, b)
    c = float(a @ b)
    if np.linalg.norm(v) < 1e-12:
        if c > 0:
            return np.eye(3)
        perp = normalize(np.cross(a, [1, 0, 0] if abs(a[0]) < 0.9 else [0, 1, 0]))
        return rotvec_to_mat(perp * np.pi)
    return rotvec_to_mat(normalize(v) * np.arctan2(np.linalg.norm(v), c))


def default_screen(width=1.2, aspect=9 / 16, distance=2.2, yaw_deg=8.0, pitch_deg=-3.0, height=0.3):
    """Screen in a gravity-aligned world (+Y up), roughly ahead along -Z."""
    R = rotvec_to_mat([0, np.radians(yaw_deg), 0]) @ rotvec_to_mat([np.radians(pitch_deg), 0, 0])
    u = R @ np.array([1.0, 0, 0])
    v = R @ np.array([0, -1.0, 0])
    c = np.array([0.15, height, -distance])
    h = width * aspect
    return Screen(c - u * width / 2 - v * h / 2, u, v, width, h)


def pose_aiming_at(barrel: Barrel, p: np.ndarray, target_w: np.ndarray, roll: float,
                   ang_noise: float = 0.0, rng=None):
    """Phone rotation R_WP such that the barrel ray from position p hits target_w."""
    R = np.eye(3)
    for _ in range(10):  # origin offset depends on R; fixed-point iterate
        o = p + R @ barrel.t
        d = normalize(target_w - o)
        R = rot_between(barrel.b, d)
        R = rotvec_to_mat(d * roll) @ R
    if ang_noise and rng is not None:
        R = rotvec_to_mat(rng.normal(0, ang_noise, 3)) @ R
    return R


def make_shot_msgs(screen: Screen, barrel: Barrel, targets, positions, rng, ang_noise=0.0,
                   pos_noise=0.0):
    """Returns list of shot dicts {q, p} aimed at the targets from the given positions."""
    out = []
    for (x, y), pos in zip(targets, positions):
        p = np.asarray(pos, float) + rng.normal(0, pos_noise, 3)
        R = pose_aiming_at(barrel, p, screen.point(x, y), rng.uniform(-np.pi, np.pi), ang_noise, rng)
        out.append({"q": mat_to_quat(R).tolist(), "p": p.tolist()})
    return out


def look_at_camera(eye, target, up=(0, 1, 0)):
    """ARCore/OpenGL camera pose (looks down -Z) at eye looking at target."""
    f = normalize(np.asarray(target, float) - eye)
    r = normalize(np.cross(f, up))
    u = np.cross(r, f)
    R_wc = np.column_stack([r, u, -f])
    return R_wc


def aruco_obs(screen: Screen, corner_px, w_px, eye, look, K=(1400, 1400, 960, 540),
              size=(1920, 1080), px_noise=0.0, rng=None):
    """Synthetic flow-A observation message for one camera view."""
    R_wc_gl = look_at_camera(np.asarray(eye, float), look)
    R_wc_cv = R_wc_gl @ np.diag([1.0, -1.0, -1.0])
    fx, fy, cx, cy = K
    m_per_px = screen.width_m / w_px
    markers, seen = {}, {}
    for (mid, k), (px, py) in corner_px.items():
        X = screen.s0 + screen.u * px * m_per_px + screen.v * py * m_per_px
        Xc = R_wc_cv.T @ (X - eye)
        if Xc[2] <= 0:
            continue
        uv = np.array([fx * Xc[0] / Xc[2] + cx, fy * Xc[1] / Xc[2] + cy])
        if px_noise and rng is not None:
            uv += rng.normal(0, px_noise, 2)
        if not (0 <= uv[0] < size[0] and 0 <= uv[1] < size[1]):
            continue
        markers.setdefault(str(mid), [0.0] * 8)
        markers[str(mid)][2 * k: 2 * k + 2] = uv.tolist()
        seen[str(mid)] = seen.get(str(mid), 0) + 1
    full = {m: c for m, c in markers.items() if seen[m] == 4}  # detector needs all 4 corners
    return {"type": "aruco_obs", "K": list(K), "size": list(size),
            "q": mat_to_quat(R_wc_gl).tolist(), "p": list(map(float, eye)), "markers": full}
