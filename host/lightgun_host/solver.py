"""Calibration solvers for flows A-D. See shared/math.md.

Single implementation: the phone only collects observations and the host solves.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.optimize import least_squares

from .geometry import (Barrel, Screen, mat_to_rotvec, normalize, orthonormalize,
                       quat_to_mat, rotvec_to_mat)


@dataclass
class Shot:
    target: tuple[float, float]   # normalized screen coords
    R: np.ndarray                 # R_WP
    p: np.ndarray                 # p_W

    @classmethod
    def from_msg(cls, target, msg) -> "Shot":
        return cls((float(target[0]), float(target[1])), quat_to_mat(msg["q"]),
                   np.asarray(msg["p"], float))


@dataclass
class SolveResult:
    ok: bool
    rms_px: float
    screen: Screen | None = None
    barrel: Barrel | None = None
    residuals_px: np.ndarray | None = None
    message: str = ""
    rms_mm: float | None = None


def _hit_residuals(screen: Screen, barrel: Barrel, shots, w_px, h_px):
    out = np.empty(2 * len(shots))
    n = screen.n
    for i, sh in enumerate(shots):
        o, d = barrel.ray(sh.R, sh.p)
        denom = n @ d
        tx, ty = sh.target
        if denom <= 1e-6:  # ray parallel or pointing away from the screen
            # smooth-ish fallback: perpendicular distance of the target to the ray
            P = screen.point(tx, ty)
            e = (P - o) - ((P - o) @ d) * d
            out[2 * i] = 10 * (screen.u @ e) / screen.width_m * w_px + w_px
            out[2 * i + 1] = 10 * (screen.v @ e) / screen.height_m * h_px + h_px
            continue
        s = n @ (screen.s0 - o) / denom
        rel = o + s * d - screen.s0
        out[2 * i] = ((screen.u @ rel) / screen.width_m - tx) * w_px
        out[2 * i + 1] = ((screen.v @ rel) / screen.height_m - ty) * h_px
    return out


def _rms(r):
    return float(np.sqrt(np.mean(np.sum(r.reshape(-1, 2) ** 2, 1)))) if len(r) else 0.0


def _lsq(fun, x0):
    method = "lm" if len(fun(x0)) >= len(x0) else "trf"
    return least_squares(fun, x0, method=method, x_scale="jac", max_nfev=2000)


# ---------------------------------------------------------------- flow C

def init_screen_pnp(shots, barrel: Barrel, w_m, h_m):
    """Planar PnP with bearing vectors (homography DLT). Needs >= 4 shots."""
    if len(shots) < 4:
        raise ValueError("need >= 4 shots")
    rays = [barrel.ray(s.R, s.p) for s in shots]
    c = np.mean([o for o, _ in rays], axis=0)
    A = []
    for (o, d), sh in zip(rays, shots):
        x = np.array([sh.target[0] * w_m, sh.target[1] * h_m, 1.0])
        dx = np.array([[0, -d[2], d[1]], [d[2], 0, -d[0]], [-d[1], d[0], 0]])
        A.append(dx @ np.kron(np.eye(3), x))
    _, _, Vt = np.linalg.svd(np.vstack(A))
    H = Vt[-1].reshape(3, 3)
    lam = 2.0 / (np.linalg.norm(H[:, 0]) + np.linalg.norm(H[:, 1]))
    H *= lam
    votes = sum(np.sign(d @ (H @ np.array([sh.target[0] * w_m, sh.target[1] * h_m, 1.0])))
                for (o, d), sh in zip(rays, shots))
    if votes < 0:
        H = -H
    R = orthonormalize(np.column_stack([H[:, 0], H[:, 1], np.cross(H[:, 0], H[:, 1])]))
    return Screen.from_pose(R, c + H[:, 2], w_m, h_m)


def solve_screen(shots, barrel: Barrel, w_m, h_m, w_px, h_px, init: Screen | None = None) -> SolveResult:
    try:
        scr0 = init or init_screen_pnp(shots, barrel, w_m, h_m)
    except (ValueError, np.linalg.LinAlgError) as e:
        return SolveResult(False, float("inf"), message=str(e))
    R0 = scr0.R

    def unpack(x):
        return Screen.from_pose(rotvec_to_mat(x[:3]) @ R0, x[3:6], w_m, h_m)

    def fun(x):
        return _hit_residuals(unpack(x), barrel, shots, w_px, h_px)

    res = _lsq(fun, np.concatenate([np.zeros(3), scr0.s0]))
    scr = unpack(res.x)
    r = fun(res.x)
    return SolveResult(True, _rms(r), screen=scr, barrel=barrel, residuals_px=r)


# ---------------------------------------------------------------- flow B

def _tangent_basis(b0):
    a = np.array([1.0, 0, 0]) if abs(b0[0]) < 0.9 else np.array([0, 1.0, 0])
    e1 = normalize(np.cross(b0, a))
    return e1, np.cross(b0, e1)


def position_spread(shots) -> float:
    P = np.array([s.p for s in shots])
    return float(np.max(np.linalg.norm(P - P.mean(0), axis=1)) * 2) if len(P) else 0.0


MAX_BARREL_OFFSET_M = 0.15  # the aim line can't be further than this from the phone's centre


def solve_barrel(shots, screen: Screen, w_px, h_px, t_init=None, fit_t: bool = False) -> SolveResult:
    t0 = np.zeros(3) if t_init is None else np.asarray(t_init, float)
    if np.linalg.norm(t0) > MAX_BARREL_OFFSET_M:
        t0 = np.zeros(3)  # a previous bad solve; don't build on it
    # Fitting the offset needs genuine parallax from two standing spots. It is only done when
    # explicitly requested: a tracking jump can fake a large position spread and the 3-DOF
    # offset then overfits wildly (seen in the field: 1.4 m offsets, 1000+ px error).
    ds = []
    for sh in shots:
        P = screen.point(*sh.target)
        ds.append(normalize(sh.R.T @ (P - sh.p - sh.R @ t0)))
    b0 = normalize(np.sum(ds, axis=0))
    e1, e2 = _tangent_basis(b0)

    def unpack(x):
        b = normalize(b0 + x[0] * e1 + x[1] * e2)
        t = x[2:5] if fit_t else t0
        return Barrel(b, t)

    def fun(x):
        r = _hit_residuals(screen, unpack(x), shots, w_px, h_px)
        if fit_t:  # weak prior keeps t sane when parallax is marginal
            r = np.concatenate([r, (x[2:5] - t0) * 20.0])
        return r

    x0 = np.concatenate([np.zeros(2), t0]) if fit_t else np.zeros(2)
    res = _lsq(fun, x0)
    barrel = unpack(res.x)
    if np.linalg.norm(barrel.t) > MAX_BARREL_OFFSET_M:  # still implausible: fall back to direction only
        return solve_barrel(shots, screen, w_px, h_px, t_init=None, fit_t=False)
    r = _hit_residuals(screen, barrel, shots, w_px, h_px)
    return SolveResult(True, _rms(r), screen=screen, barrel=barrel, residuals_px=r)


# ---------------------------------------------------------------- flow D

def solve_joint(shots, w_px, h_px, barrel_init: Barrel, fit_t=False, width_init=1.2,
                distance_init=2.0) -> SolveResult:
    aspect = h_px / w_px
    rays = [barrel_init.ray(s.R, s.p) for s in shots]
    dbar = normalize(np.mean([d for _, d in rays], axis=0))
    obar = np.mean([o for o, _ in rays], axis=0)
    fwd = dbar.copy()
    fwd[1] = 0.0
    fwd = normalize(fwd) if np.linalg.norm(fwd) > 1e-3 else np.array([0, 0, -1.0])
    v = np.array([0, -1.0, 0])
    u = normalize(np.cross(v, fwd))  # so that n = u x v = fwd (into the screen)
    R0 = np.column_stack([u, v, np.cross(u, v)])
    c0 = obar + distance_init * dbar
    w0 = width_init
    s0_0 = c0 - u * w0 / 2 - v * w0 * aspect / 2
    b0 = barrel_init.b
    e1, e2 = _tangent_basis(b0)
    t0 = barrel_init.t

    def unpack(x):
        w = float(np.exp(x[6])) * w0
        scr = Screen.from_pose(rotvec_to_mat(x[:3]) @ R0, x[3:6], w, w * aspect)
        b = normalize(b0 + x[7] * e1 + x[8] * e2)
        t = x[9:12] if fit_t else t0
        return scr, Barrel(b, t)

    def fun(x):
        scr, bar = unpack(x)
        r = _hit_residuals(scr, bar, shots, w_px, h_px)
        if fit_t:
            r = np.concatenate([r, (x[9:12] - t0) * 20.0])
        return r

    x0 = np.concatenate([np.zeros(3), s0_0, [0.0], np.zeros(2)] + ([t0] if fit_t else []))
    res = _lsq(fun, x0)
    scr, bar = unpack(res.x)
    r = _hit_residuals(scr, bar, shots, w_px, h_px)
    ok = position_spread(shots) > 0.5
    msg = "" if ok else "shots from a single position: screen size is poorly constrained"
    return SolveResult(ok, _rms(r), screen=scr, barrel=bar, residuals_px=r, message=msg)


# ---------------------------------------------------------------- flow A

@dataclass
class CameraView:
    K: np.ndarray        # 3x3
    R_cw: np.ndarray     # world -> OpenCV camera
    t_cw: np.ndarray
    corners: dict        # (marker_id, corner_idx) -> (u, v)

    @classmethod
    def from_msg(cls, msg) -> "CameraView":
        fx, fy, cx, cy = msg["K"]
        K = np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1.0]])
        R_wc = quat_to_mat(msg["q"]) @ np.diag([1.0, -1.0, -1.0])  # GL -> CV
        p = np.asarray(msg["p"], float)
        R_cw = R_wc.T
        corners = {}
        for mid, pts in msg["markers"].items():
            for k in range(4):
                corners[(int(mid), k)] = (float(pts[2 * k]), float(pts[2 * k + 1]))
        return cls(K, R_cw, -R_cw @ p, corners)

    @property
    def P(self):
        return self.K @ np.column_stack([self.R_cw, self.t_cw])

    @property
    def center_w(self):
        return -self.R_cw.T @ self.t_cw

    def project(self, X):
        Xc = self.R_cw @ X + self.t_cw
        if Xc[2] <= 1e-6:
            return None
        return (self.K @ (Xc / Xc[2]))[:2]


def triangulate(views, key, max_reproj=4.0):
    obs = [(v, v.corners[key]) for v in views if key in v.corners]
    if len(obs) < 2:
        return None
    for _ in range(2):
        A = []
        for v, (u, w) in obs:
            P = v.P
            A.append(u * P[2] - P[0])
            A.append(w * P[2] - P[1])
        _, _, Vt = np.linalg.svd(np.array(A))
        X = Vt[-1][:3] / Vt[-1][3]
        keep = []
        for v, uv in obs:
            pr = v.project(X)
            if pr is not None and np.linalg.norm(pr - np.array(uv)) < max_reproj:
                keep.append((v, uv))
        if len(keep) == len(obs) or len(keep) < 2:
            break
        obs = keep
    return X, len(obs)


def umeyama(src: np.ndarray, dst: np.ndarray):
    """dst ~ s * R @ src + t. Returns s, R, t."""
    mu_s, mu_d = src.mean(0), dst.mean(0)
    xs, xd = src - mu_s, dst - mu_d
    cov = xd.T @ xs / len(src)
    U, D, Vt = np.linalg.svd(cov)
    S = np.eye(3)
    if np.linalg.det(U) * np.linalg.det(Vt) < 0:
        S[2, 2] = -1
    R = U @ S @ Vt
    var_s = (xs ** 2).sum() / len(src)
    s = np.trace(np.diag(D) @ S) / var_s
    return s, R, mu_d - s * R @ mu_s


def view_baseline(views) -> float:
    C = np.array([v.center_w for v in views])
    return float(np.max(np.linalg.norm(C - C.mean(0), axis=1)) * 2) if len(C) else 0.0


# 80th-percentile reprojection error of the fitted screen, as an angle so it is independent of
# camera resolution. Phone tracking jitter alone is ~0.2-0.3 deg; 0.5 deg still fits the screen
# size to well under 1%.
ARUCO_MAX_REPROJ_DEG = 0.5


def solve_screen_aruco(views, corner_px: dict, w_px, h_px, min_views=10) -> SolveResult:
    """corner_px: (marker_id, corner_idx) -> (px, py) on the display."""
    if len(views) < min_views:
        return SolveResult(False, float("inf"), message=f"need {min_views} views, have {len(views)}")
    src, dst, keys = [], [], []
    for key, (px, py) in corner_px.items():
        tri = triangulate(views, key)
        if tri is None or tri[1] < 3:
            continue
        src.append([px, py, 0.0])
        dst.append(tri[0])
        keys.append(key)
    if len(keys) < 8:
        return SolveResult(False, float("inf"), message=f"only {len(keys)} marker corners triangulated")
    src, dst = np.array(src), np.array(dst)
    m, R, t = umeyama(src, dst)
    # The screen frame must be u right, v down, n = u x v (det +1 already by Umeyama).
    R0 = R

    def unpack(x):
        return rotvec_to_mat(x[:3]) @ R0, x[3:6], float(np.exp(x[6])) * m

    def fun(x):
        Rr, s0, mm = unpack(x)
        out = []
        for key in keys:
            px, py = corner_px[key]
            X = s0 + Rr @ np.array([px * mm, py * mm, 0.0])
            for v in views:
                if key in v.corners:
                    pr = v.project(X)
                    uv = v.corners[key]
                    if pr is None:
                        out += [50.0, 50.0]
                    else:
                        out += [pr[0] - uv[0], pr[1] - uv[1]]
        return np.array(out)

    res = least_squares(fun, np.concatenate([np.zeros(3), t, [0.0]]), loss="huber", f_scale=2.0)
    Rr, s0, mm = unpack(res.x)
    # 3-D residual of triangulated corners vs fitted rectangle
    fitted = np.array([s0 + Rr @ (np.array(p) * [mm, mm, 0]) for p in src])
    rms_mm = float(np.sqrt(np.mean(np.sum((fitted - dst) ** 2, 1)))) * 1000
    scr = Screen.from_pose(Rr, s0, mm * w_px, mm * h_px)
    reproj = fun(res.x)
    # Accept on how well the fitted screen re-projects into the camera images. The 3-D
    # residual above is dominated by per-corner depth noise from a small sweep baseline and
    # badly overstates the error of the jointly fitted rectangle; it is reported only.
    err = np.hypot(reproj[0::2], reproj[1::2])
    fx = float(np.mean([v.K[0, 0] for v in views]))
    p80 = float(np.percentile(err, 80)) if len(err) else float("inf")
    p80_deg = float(np.degrees(p80 / fx))
    ok = p80_deg < ARUCO_MAX_REPROJ_DEG
    msg = "" if ok else (f"markers don't line up ({p80_deg:.2f} deg): move slower, pause between steps, "
                         f"and keep all 5 squares in view")
    r = SolveResult(ok, _rms(reproj), screen=scr, rms_mm=rms_mm, message=msg)
    r.reproj_p80 = p80
    return r


def solve_screen_aruco_known(views, corner_px: dict, w_px, h_px, w_m, h_m, min_views=3) -> SolveResult:
    """Quick lock (flow Q): place a screen of KNOWN size from a few camera views of the markers.

    Unlike flow A this needs no sweep: every single view already fixes the screen pose by
    planar PnP, and a handful of views are fitted jointly to average out noise.
    """
    import cv2
    good = [v for v in views if len(v.corners) >= 8]
    if len(good) < min_views:
        return SolveResult(False, float("inf"), message=f"need {min_views} clear views of the markers")
    mx, my = w_m / w_px, h_m / h_px
    keys = [k for k in corner_px]

    def obj(key):
        px, py = corner_px[key]
        return np.array([px * mx, py * my, 0.0])

    # initial pose from the view that sees the most corners
    v0 = max(good, key=lambda v: len(v.corners))
    ks = [k for k in keys if k in v0.corners]
    ok, rvec, tvec = cv2.solvePnP(np.array([obj(k) for k in ks]), np.array([v0.corners[k] for k in ks]),
                                  v0.K, None, flags=cv2.SOLVEPNP_IPPE)
    if not ok:
        return SolveResult(False, float("inf"), message="could not see the screen clearly")
    R_sc = cv2.Rodrigues(rvec)[0]
    R0 = v0.R_cw.T @ R_sc                                  # screen -> world
    s00 = v0.R_cw.T @ (tvec.ravel() - v0.t_cw)             # top-left corner in world

    def unpack(x):
        return rotvec_to_mat(x[:3]) @ R0, s00 + x[3:6]

    def fun(x):
        Rr, s0 = unpack(x)
        out = []
        for key in keys:
            X = s0 + Rr @ obj(key)
            for v in good:
                if key in v.corners:
                    pr = v.project(X)
                    uv = v.corners[key]
                    out += [50.0, 50.0] if pr is None else [pr[0] - uv[0], pr[1] - uv[1]]
        return np.array(out)

    res = least_squares(fun, np.zeros(6), loss="huber", f_scale=2.0)
    Rr, s0 = unpack(res.x)
    reproj = fun(res.x)
    err = np.hypot(reproj[0::2], reproj[1::2])
    fx = float(np.mean([v.K[0, 0] for v in good]))
    p80 = float(np.percentile(err, 80))
    p80_deg = float(np.degrees(p80 / fx))
    ok = p80_deg < ARUCO_MAX_REPROJ_DEG
    r = SolveResult(ok, _rms(reproj), screen=Screen.from_pose(Rr, s0, w_m, h_m),
                    message="" if ok else f"markers don't line up ({p80_deg:.2f} deg) - hold steadier")
    r.reproj_p80 = p80
    return r
