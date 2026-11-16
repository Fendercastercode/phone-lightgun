"""Accuracy acceptance test (spec: <= 0.5% of screen width RMS at 2 m after flow C).

Starts the host; then click "Accuracy test (30 shots)" in the host window (or run this
script, which presses it for you). 30 random targets appear; aim and pull the trigger at
each. The host logs the RMS error and writes accuracy_<timestamp>.csv in the current dir.

For the drift test, run it right after flow C and again after 30 minutes of play without
recentering; compare the two RMS values (target: < 0.5% change).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "host"))

from PySide6.QtCore import QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from lightgun_host import app as host_app  # noqa: E402


def main():
    import logging
    logging.basicConfig(level=logging.INFO)
    args = host_app.argparse.Namespace(port=None, monitor=None, new_key=False, log=None, dry_run=False,
                                       verbose=False)
    qapp = QApplication(sys.argv[:1])
    w = host_app.HostWindow(args)
    w.show()
    print("Waiting for the phone; the test starts 3 s after it connects (flow C must be done).")

    def maybe_start():
        if any(g.count for g in w.rx.guns.values()):
            w._accuracy_test()
        else:
            QTimer.singleShot(3000, maybe_start)

    QTimer.singleShot(3000, maybe_start)
    sys.exit(qapp.exec())


if __name__ == "__main__":
    main()
