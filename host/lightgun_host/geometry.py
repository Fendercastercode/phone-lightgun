"""Shared geometry: rotations, screen model, barrel, ray-plane intersection.

Conventions are documented in shared/math.md. Quaternions are [x, y, z, w].
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


def normalize(v: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(v)
    if n < 1e-15:
        raise ValueError("zero-length vector")
    return v / n


def quat_to_mat(q) -> np.ndarray:
    x, y, z, w = (float(c) for c in q)
    n = x * x + y * y + z * z + w * w
    s = 2.0 / n
    return np.array([
        [1 - s * (y * y + z * z), s * (x * y - z * w), s * (x * z + y * w)],
        [s * (x * y + z * w), 1 - s * (x * x + z * z), s * (y * z - x * w)],
        [s * (x * z - y * w), s * (y * z + x * w), 1 - s * (x * x + y * y)],
    ])


def mat_to_quat(R: np.ndarray) -> np.ndarray:
    """Rotation matrix -> [x, y, z, w] (w >= 0)."""
    m = R
    tr = m[0, 0] + m[1, 1] + m[2, 2]
    if tr > 0:
        s = np.sqrt(tr + 1.0) * 2
        w = 0.25 * s
        x = (m[2, 1] - m[1, 2]) / s
        y = (m[0, 2] - m[2, 0]) / s
        z = (m[1, 0] - m[0, 1]) / s
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        w = (m[2, 1] - m[1, 2]) / s
        x = 0.25 * s
        y = (m[0, 1] + m[1, 0]) / s
        z = (m[0, 2] + m[2, 0]) / s
    elif m[1, 1] > m[2, 2]:
        s = np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        w = (m[0, 2] - m[2, 0]) / s
        x = (m[0, 1] + m[1, 0]) / s
        y = 0.25 * s
        z = (m[1, 2] + m[2, 1]) / s
    else:
        s = np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        w = (m[1, 0] - m[0, 1]) / s
        x = (m[0, 2] + m[2, 0]) / s
        y = (m[1, 2] + m[2, 1]) / s
        z = 0.25 * s
    q = np.array([x, y, z, w])
    q /= np.linalg.norm(q)
    return q if q[3] >= 0 else -q


def rotvec_to_mat(w) -> np.ndarray:
    w = np.asarray(w, dtype=float)
    th = np.linalg.norm(w)
    K = np.array([[0, -w[2], w[1]], [w[2], 0, -w[0]], [-w[1], w[0], 0]])
    if th < 1e-12:
        return np.eye(3) + K
    K /= th
    return np.eye(3) + np.sin(th) * K + (1 - np.cos(th)) * (K @ K)


def mat_to_rotvec(R: np.ndarray) -> np.ndarray:
    c = np.clip((np.trace(R) - 1) / 2, -1.0, 1.0)
    th = np.arccos(c)
    if th < 1e-9:
        return np.array([R[2, 1] - R[1, 2], R[0, 2] - R[2, 0], R[1, 0] - R[0, 1]]) / 2
    if np.pi - th < 1e-6:
        # near 180 degrees: axis from the symmetric part
        A = (R + np.eye(3)) / 2
        axis = normalize(A[:, int(np.argmax(np.diag(A)))])
        return axis * th
    axis = np.array([R[2, 1] - R[1, 2], R[0, 2] - R[2, 0], R[1, 0] - R[0, 1]]) / (2 * np.sin(th))
    return axis * th


def orthonormalize(M: np.ndarray) -> np.ndarray:
    U, _, Vt = np.linalg.svd(M)
    R = U @ Vt
    if np.linalg.det(R) < 0:
        U[:, -1] *= -1
        R = U @ Vt
    return R


@dataclass
class Barrel:
    b: np.ndarray = field(default_factory=lambda: np.array([0.0, 0.0, -1.0]))
    t: np.ndarray = field(default_factory=lambda: np.zeros(3))

    def __post_init__(self):
        self.b = normalize(np.asarray(self.b, dtype=float))
        self.t = np.asarray(self.t, dtype=float)

    def ray(self, R_wp: np.ndarray, p_w: np.ndarray):
        return p_w + R_wp @ self.t, R_wp @ self.b

    def to_dict(self):
        return {"b": self.b.tolist(), "t": self.t.tolist()}

    @classmethod
    def from_dict(cls, d):
        return cls(np.array(d["b"], float), np.array(d.get("t", [0, 0, 0]), float))


@dataclass
class Screen:
    s0: np.ndarray
    u: np.ndarray
    v: np.ndarray
    width_m: float
    height_m: float

    @property
    def n(self) -> np.ndarray:
        return np.cross(self.u, self.v)

    @property
    def R(self) -> np.ndarray:
        return np.column_stack([self.u, self.v, self.n])

    @property
    def center(self) -> np.ndarray:
        return self.point(0.5, 0.5)

    def point(self, x: float, y: float) -> np.ndarray:
        return self.s0 + self.u * (x * self.width_m) + self.v * (y * self.height_m)

    def intersect(self, o: np.ndarray, d: np.ndarray):
        """Returns (x, y, s) or None if the ray misses the plane / points away."""
        n = self.n
        denom = float(n @ d)
        if abs(denom) < 1e-9:
            return None
        s = float(n @ (self.s0 - o)) / denom
        if s <= 0:
            return None
        h = o + s * d
        rel = h - self.s0
        return float(self.u @ rel) / self.width_m, float(self.v @ rel) / self.height_m, s

    @classmethod
    def from_pose(cls, R: np.ndarray, s0: np.ndarray, w: float, h: float) -> "Screen":
        return cls(np.asarray(s0, float), R[:, 0].copy(), R[:, 1].copy(), float(w), float(h))

    def to_dict(self):
        return {"s0": self.s0.tolist(), "u": self.u.tolist(), "v": self.v.tolist(),
                "w_m": self.width_m, "h_m": self.height_m}

    @classmethod
    def from_dict(cls, d):
        return cls(np.array(d["s0"], float), np.array(d["u"], float), np.array(d["v"], float),
                   float(d["w_m"]), float(d["h_m"]))


def aim_point(screen: Screen, barrel: Barrel, R_wp, p_w):
    """Full pipeline used by both sides: pose -> (x, y, onScreen) or None."""
    o, d = barrel.ray(R_wp, p_w)
    hit = screen.intersect(o, d)
    if hit is None:
        return None
    x, y, _ = hit
    return x, y, (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0)
