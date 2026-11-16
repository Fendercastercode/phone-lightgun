import numpy as np

from lightgun_host.calibration import TARGETS_B, Calibrator
from lightgun_host.geometry import Barrel
from lightgun_host.sim import default_screen, make_shot_msgs

BARREL = Barrel(np.array([0.0, 1.0, 0.0]), np.array([0.02, -0.04, 0.01]))
W_PX, H_PX = 1920, 1080


class FakeUI:
    def show_targets(self, targets, current, message):
        pass

    def show_markers(self, markers, message, progress):
        pass

    def show_message(self, message, seconds=0):
        pass

    def hide(self):
        pass


class FakeStore:
    def screen_size(self, display_id):
        return (1.2, 0.675)


def _make_calibrator():
    sent = []
    injection = []
    calib = Calibrator(FakeStore(), "disp0", W_PX, H_PX, sent.append, FakeUI(), injection.append)
    return calib, sent, injection


def _feed_flow_b(calib, had_barrel: bool, ang_noise: float, rng):
    calib.screen = default_screen()
    calib.start({"type": "calib_request", "flow": "B", "barrel": BARREL.to_dict() if had_barrel else None})
    pos = [rng.normal(0, 0.03, 3) for _ in TARGETS_B]
    msgs = make_shot_msgs(calib.screen, BARREL, TARGETS_B, pos, rng, ang_noise=ang_noise)
    for i, m in enumerate(msgs):
        m["index"] = i
        calib.on_shot(m)


def test_flow_b_failure_keeps_previous_calibration_and_says_so():
    # Big angular noise pushes the fit's RMS over the rejection threshold.
    calib, sent, injection = _make_calibrator()
    _feed_flow_b(calib, had_barrel=True, ang_noise=np.radians(5), rng=np.random.default_rng(7))
    errors = [m for m in sent if m.get("type") == "calib_error"]
    assert errors, "expected flow B to fail with this much shot noise"
    assert "kept" in errors[-1]["message"].lower()
    assert "barrel" not in errors[-1]            # failure never sends a new barrel to apply
    assert injection and injection[-1] is True   # cursor resumes using the untouched old calibration


def test_flow_b_failure_without_a_prior_barrel_says_nothing_was_kept():
    calib, sent, injection = _make_calibrator()
    _feed_flow_b(calib, had_barrel=False, ang_noise=np.radians(5), rng=np.random.default_rng(7))
    errors = [m for m in sent if m.get("type") == "calib_error"]
    assert errors
    assert "kept" not in errors[-1]["message"].lower()


def test_flow_b_success_still_works():
    calib, sent, injection = _make_calibrator()
    _feed_flow_b(calib, had_barrel=True, ang_noise=0.0, rng=np.random.default_rng(8))
    results = [m for m in sent if m.get("type") == "calib_result"]
    assert results and results[-1]["ok"] is True
    assert "barrel" in results[-1]
