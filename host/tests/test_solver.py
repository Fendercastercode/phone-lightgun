import numpy as np
import pytest

from lightgun_host import solver
from lightgun_host.calibration import TARGETS_B, TARGETS_C, TARGETS_D, marker_layout
from lightgun_host.geometry import (Barrel, aim_point, mat_to_quat, mat_to_rotvec, quat_to_mat,
                                    rotvec_to_mat)
from lightgun_host.sim import aruco_obs, default_screen, make_shot_msgs

W_PX, H_PX = 1920, 1080
BARREL = Barrel(np.array([0.0, 1.0, 0.0]), np.array([0.02, -0.04, 0.01]))  # top-flat grip


def shots_for(screen, barrel, targets, positions, rng, noise=0.0):
    msgs = make_shot_msgs(screen, barrel, targets, positions, rng, ang_noise=noise, pos_noise=0.0)
    return [solver.Shot.from_msg(t, m) for t, m in zip(targets, msgs)]


def test_rotation_roundtrips():
    rng = np.random.default_rng(1)
    for _ in range(50):
        w = rng.normal(size=3)
        R = rotvec_to_mat(w)
        assert np.allclose(quat_to_mat(mat_to_quat(R)), R, atol=1e-9)
        assert np.allclose(rotvec_to_mat(mat_to_rotvec(R)), R, atol=1e-7)


def test_shots_hit_their_targets():
    rng = np.random.default_rng(2)
    scr = default_screen()
    for sh in shots_for(scr, BARREL, TARGETS_C, [np.zeros(3)] * 4, rng):
        x, y, on = aim_point(scr, BARREL, sh.R, sh.p)
        assert on and abs(x - sh.target[0]) < 1e-7 and abs(y - sh.target[1]) < 1e-7


def screen_err_px(a, b):
    errs = []
    for x, y in [(0, 0), (1, 0), (1, 1), (0, 1), (0.5, 0.5)]:
        errs.append(np.linalg.norm(a.point(x, y) - b.point(x, y)) / a.width_m * W_PX)
    return max(errs)


def test_flow_c_exact():
    rng = np.random.default_rng(3)
    scr = default_screen()
    pos = [rng.normal(0, 0.03, 3) for _ in TARGETS_C]
    r = solver.solve_screen(shots_for(scr, BARREL, TARGETS_C, pos, rng), BARREL,
                            scr.width_m, scr.height_m, W_PX, H_PX)
    assert r.ok and r.rms_px < 1e-3
    assert screen_err_px(scr, r.screen) < 0.5


def test_flow_c_noisy_meets_aim_target():
    rng = np.random.default_rng(4)
    scr = default_screen()
    noise = np.radians(0.05)
    pos = [rng.normal(0, 0.03, 3) for _ in TARGETS_C + [(0.5, 0.5)]]
    r = solver.solve_screen(shots_for(scr, BARREL, TARGETS_C + [(0.5, 0.5)], pos, rng, noise), BARREL,
                            scr.width_m, scr.height_m, W_PX, H_PX)
    assert r.ok
    # aim error at random targets from the standing position (what the player sees)
    test = [(rng.uniform(.1, .9), rng.uniform(.1, .9)) for _ in range(30)]
    errs = []
    for sh in shots_for(scr, BARREL, test, [np.zeros(3)] * 30, rng):
        x, y, _ = aim_point(r.screen, BARREL, sh.R, sh.p)
        errs.append(np.hypot(x - sh.target[0], (y - sh.target[1]) * H_PX / W_PX))
    assert np.sqrt(np.mean(np.square(errs))) < 0.005  # acceptance: <= 0.5% of width


def test_flow_b_direction_only():
    rng = np.random.default_rng(5)
    scr = default_screen()
    pos = [rng.normal(0, 0.03, 3) for _ in TARGETS_B]
    r = solver.solve_barrel(shots_for(scr, BARREL, TARGETS_B, pos, rng), scr, W_PX, H_PX,
                            t_init=BARREL.t, fit_t=False)
    assert np.degrees(np.arccos(np.clip(r.barrel.b @ BARREL.b, -1, 1))) < 0.01
    assert r.rms_px < 0.01


def test_flow_b_with_offset_two_positions():
    rng = np.random.default_rng(6)
    scr = default_screen()
    targets = TARGETS_B + TARGETS_B
    pos = [np.array([-0.6, 0, 0])] * 5 + [np.array([0.6, 0, 0.3])] * 5
    r = solver.solve_barrel(shots_for(scr, BARREL, targets, pos, rng), scr, W_PX, H_PX,
                            t_init=np.zeros(3), fit_t=True)
    assert r.rms_px < 0.5
    assert np.degrees(np.arccos(np.clip(r.barrel.b @ BARREL.b, -1, 1))) < 0.2


def test_flow_d_joint():
    rng = np.random.default_rng(7)
    scr = default_screen(width=1.43)
    pos = [np.array([-0.6, 0.0, 0.0])] * 9 + [np.array([0.6, 0.05, 0.2])] * 9
    true_barrel = Barrel(np.array([0.0, 1.0, 0.0]), np.zeros(3))
    init_barrel = Barrel(rotvec_to_mat([0.05, 0, 0.04]) @ np.array([0.0, 1.0, 0.0]), np.zeros(3))
    r = solver.solve_joint(shots_for(scr, true_barrel, TARGETS_D, pos, rng), W_PX, H_PX, init_barrel,
                           width_init=1.0)
    assert r.ok and r.rms_px < 0.5
    assert abs(r.screen.width_m - 1.43) < 0.01


def test_flow_a_aruco():
    rng = np.random.default_rng(8)
    scr = default_screen(width=1.21)
    layout = marker_layout(W_PX, H_PX)
    corner_px = {(m.id, k): c for m in layout for k, c in enumerate(m.corners())}
    views = []
    for i in range(30):
        eye = np.array([rng.uniform(-0.4, 0.4), rng.uniform(0.0, 0.5), rng.uniform(-0.8, 0.0)])
        msg = aruco_obs(scr, corner_px, W_PX, eye, scr.center + rng.normal(0, 0.05, 3),
                        px_noise=0.3, rng=rng)
        views.append(solver.CameraView.from_msg(msg))
    r = solver.solve_screen_aruco(views, corner_px, W_PX, H_PX)
    assert r.ok, r.message
    assert abs(r.screen.width_m - 1.21) < 0.003
    assert screen_err_px(scr, r.screen) < 3
    assert r.screen.u @ scr.u > 0.999 and r.screen.v @ scr.v > 0.999


def test_flow_c_needs_four():
    with pytest.raises(ValueError):
        solver.init_screen_pnp([], BARREL, 1, 1)


def test_flow_a_small_sweep_with_tracking_noise():
    """Real-world case: ~25 cm sweep and a few mm of phone-pose noise. Per-corner 3-D residual
    is large, but the jointly fitted screen must still pass and be accurate."""
    rng = np.random.default_rng(9)
    scr = default_screen(width=1.21)
    layout = marker_layout(W_PX, H_PX)
    corner_px = {(m.id, k): c for m in layout for k, c in enumerate(m.corners())}
    views = []
    for i in range(40):
        true_eye = np.array([rng.uniform(-0.12, 0.12), 0.25 + rng.uniform(-0.06, 0.06), -0.6])
        msg = aruco_obs(scr, corner_px, W_PX, true_eye, scr.center, px_noise=0.6, rng=rng)
        msg["p"] = list(true_eye + rng.normal(0, 0.004, 3))  # ARCore position noise
        views.append(solver.CameraView.from_msg(msg))
    r = solver.solve_screen_aruco(views, corner_px, W_PX, H_PX)
    assert r.ok, r.message
    assert abs(r.screen.width_m - 1.21) < 0.012  # within 1%


def test_flow_b_rejects_tracking_jump_and_bad_init():
    """Field bug: a tracking jump between shots + a previous garbage offset produced a 1.4 m
    offset and 1000+ px error that was still reported OK. Now: offset never auto-fitted,
    garbage init ignored, direction still recovered."""
    rng = np.random.default_rng(10)
    scr = default_screen()
    pos = [np.zeros(3)] * 5
    shots = shots_for(scr, BARREL, TARGETS_B, pos, rng)
    for sh in shots[3:]:  # ARCore position jumped 0.8 m for the last shots (orientation fine)
        sh.p = sh.p + np.array([0.8, 0.0, 0.0])
    r = solver.solve_barrel(shots, scr, W_PX, H_PX, t_init=np.array([1.41, 1.21, 0.3]))
    assert np.linalg.norm(r.barrel.t) <= solver.MAX_BARREL_OFFSET_M
    good = solver.solve_barrel(shots_for(scr, BARREL, TARGETS_B, pos, rng), scr, W_PX, H_PX,
                               t_init=np.array([1.41, 1.21, 0.3]))
    assert good.rms_px < 20 and np.linalg.norm(good.barrel.t) < 1e-9


def test_flow_q_quick_lock_known_size():
    """Quick lock: 4 nearly identical views from where the player stands, known size."""
    rng = np.random.default_rng(11)
    scr = default_screen(width=1.21)
    layout = marker_layout(W_PX, H_PX)
    corner_px = {(m.id, k): c for m in layout for k, c in enumerate(m.corners())}
    views = []
    for i in range(4):
        eye = np.array([0.3, 0.2, -0.4]) + rng.normal(0, 0.005, 3)
        msg = aruco_obs(scr, corner_px, W_PX, eye, scr.center + rng.normal(0, 0.03, 3), px_noise=0.5, rng=rng)
        msg["p"] = list(eye + rng.normal(0, 0.003, 3))
        views.append(solver.CameraView.from_msg(msg))
    r = solver.solve_screen_aruco_known(views, corner_px, W_PX, H_PX, scr.width_m, scr.height_m)
    assert r.ok, r.message
    assert screen_err_px(scr, r.screen) < 15  # < 1% of width
    assert r.screen.u @ scr.u > 0.999
