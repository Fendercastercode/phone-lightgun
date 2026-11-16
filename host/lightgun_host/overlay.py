"""Fullscreen overlay on the target monitor: QR pairing, calibration targets, ArUco markers."""
from __future__ import annotations

import sys

from PySide6.QtCore import QRectF, Qt, QTimer
from PySide6.QtGui import QColor, QFont, QImage, QPainter, QPen
from PySide6.QtWidgets import QWidget

try:
    import cv2  # opencv-python >= 4.7 ships cv2.aruco
    _ARUCO = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
except Exception:  # pragma: no cover
    cv2 = None
    _ARUCO = None

try:
    import qrcode
except ImportError:  # pragma: no cover
    qrcode = None


def marker_image(mid: int, size: int) -> QImage:
    if _ARUCO is None:
        raise RuntimeError("opencv-python is required for flow A markers")
    img = cv2.aruco.generateImageMarker(_ARUCO, mid, size)
    img = img.copy()
    return QImage(img.data, size, size, size, QImage.Format_Grayscale8).copy()


def _foreground_window():
    """Window the player is using (the emulator): an HWND on Windows, an X11 window id on Linux."""
    if sys.platform.startswith("linux"):
        import shutil
        import subprocess
        if not shutil.which("xdotool"):
            return None
        try:
            out = subprocess.run(["xdotool", "getactivewindow"], capture_output=True, text=True, timeout=0.5).stdout
            return int(out.strip()) if out.strip() else None
        except (OSError, ValueError, subprocess.SubprocessError):
            return None
    if sys.platform != "win32":
        return None
    import ctypes
    return ctypes.windll.user32.GetForegroundWindow() or None


def _give_back_focus(hwnd):
    """Bring the emulator back after the overlay: restore it if a fullscreen game minimised
    itself when the overlay covered it, then make it the foreground window again."""
    if sys.platform.startswith("linux") and hwnd:
        import subprocess
        try:
            subprocess.run(["xdotool", "windowactivate", str(hwnd)], capture_output=True, timeout=1)
        except (OSError, subprocess.SubprocessError):
            pass
        return
    if sys.platform != "win32" or not hwnd:
        return
    import ctypes
    u = ctypes.windll.user32
    if not u.IsWindow(hwnd):
        return
    if u.IsIconic(hwnd):
        u.ShowWindow(hwnd, 9)                      # SW_RESTORE
    # Windows only lets the process that last had input change the foreground window;
    # a synthetic Alt tap (as Windows itself recommends) lifts that lock.
    VK_MENU, KEYUP = 0x12, 0x0002
    u.keybd_event(VK_MENU, 0, 0, 0)
    u.keybd_event(VK_MENU, 0, KEYUP, 0)
    u.SetForegroundWindow(hwnd)


class Overlay(QWidget):
    def __init__(self):
        super().__init__(None, Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setCursor(Qt.BlankCursor)
        self.mode = "hidden"
        self.message = ""
        self.targets = []
        self.current = 0
        self.markers = []
        self.progress = 0.0
        self.qr_text = ""
        self.qr_caption = ""
        self._marker_cache = {}
        self._timer = QTimer(self, singleShot=True, timeout=self.hide)

    # ------------------------------------------------------------ placement
    def place_on(self, qscreen):
        self.setScreen(qscreen)
        self.setGeometry(qscreen.geometry())

    def _show(self):
        self._timer.stop()
        if not self.isVisible():
            fg = _foreground_window()
            self._return_to = fg if fg and fg != int(self.winId()) else None
            self.showFullScreen()
        self.raise_()
        self.update()

    # ------------------------------------------------------------ CalibUI
    def show_targets(self, targets, current, message):
        self.mode, self.targets, self.current, self.message = "targets", targets, current, message
        self._show()

    def show_markers(self, markers, message, progress):
        self.mode, self.markers, self.message, self.progress = "markers", markers, message, progress
        self._show()

    def show_message(self, message, seconds=0):
        self.mode, self.message = "message", message
        self._show()
        if seconds:
            self._timer.start(int(seconds * 1000))

    def show_qr(self, text, caption):
        self.mode, self.qr_text, self.qr_caption = "qr", text, caption
        self._show()

    def hide(self):
        was = self.isVisible()
        self.mode = "hidden"
        super().hide()
        if was:
            _give_back_focus(getattr(self, "_return_to", None))
            self._return_to = None

    def keyPressEvent(self, ev):
        if ev.key() == Qt.Key_Escape:
            self.hide()

    # ------------------------------------------------------------ painting
    def paintEvent(self, _ev):
        p = QPainter(self)
        dpr = self.devicePixelRatioF()
        W, H = self.width(), self.height()            # logical
        Wp, Hp = W * dpr, H * dpr                       # physical
        p.fillRect(self.rect(), QColor(0, 0, 0) if self.mode != "markers" else QColor(255, 255, 255))
        if self.mode == "targets":
            for i, (x, y) in enumerate(self.targets):
                cx, cy = x * W, y * H
                if i == self.current:
                    self._crosshair(p, cx, cy, min(W, H) * 0.06, QColor(255, 60, 60), 3)
                elif i > self.current and (x, y) not in self.targets[:self.current]:
                    p.setPen(QPen(QColor(90, 90, 90), 1))
                    p.drawEllipse(QRectF(cx - 6, cy - 6, 12, 12))
        elif self.mode == "markers":
            p.setRenderHint(QPainter.SmoothPixmapTransform, False)
            for m in self.markers:
                img = self._marker_cache.get((m.id, m.size))
                if img is None:
                    img = self._marker_cache[(m.id, m.size)] = marker_image(m.id, m.size)
                p.drawImage(QRectF(m.x / dpr, m.y / dpr, m.size / dpr, m.size / dpr), img)
            # progress bar along the top edge (inside the quiet zone)
            p.fillRect(QRectF(W * 0.3, H * 0.02, W * 0.4, 6), QColor(220, 220, 220))
            p.fillRect(QRectF(W * 0.3, H * 0.02, W * 0.4 * self.progress, 6), QColor(30, 160, 60))
        elif self.mode == "qr" and qrcode is not None:
            q = qrcode.QRCode(border=4, error_correction=qrcode.constants.ERROR_CORRECT_M)
            q.add_data(self.qr_text)
            mat = q.get_matrix()
            n = len(mat)
            side = min(W, H) * 0.6
            cell = side / n
            ox, oy = (W - side) / 2, (H - side) / 2 - H * 0.05
            p.fillRect(QRectF(ox, oy, side, side), QColor(255, 255, 255))
            for r, row in enumerate(mat):
                for c, v in enumerate(row):
                    if v:
                        p.fillRect(QRectF(ox + c * cell, oy + r * cell, cell + 0.5, cell + 0.5), Qt.black)
            self._text(p, self.qr_caption, oy + side + H * 0.03, QColor(230, 230, 230))
        color = QColor(30, 30, 30) if self.mode == "markers" else QColor(220, 220, 220)
        y = H * 0.5 - 40 if self.mode == "message" else H - H * 0.06 - 40
        if self.mode == "markers":
            y = H * 0.27  # between the top markers and the centre marker
        self._text(p, self.message, y, color, big=self.mode == "message")
        p.end()

    def _crosshair(self, p, cx, cy, r, color, w):
        p.setRenderHint(QPainter.Antialiasing, True)
        p.setPen(QPen(color, w))
        p.drawEllipse(QRectF(cx - r, cy - r, 2 * r, 2 * r))
        p.drawEllipse(QRectF(cx - r / 2, cy - r / 2, r, r))
        p.drawLine(int(cx - r * 1.4), int(cy), int(cx - r * 0.2), int(cy))
        p.drawLine(int(cx + r * 0.2), int(cy), int(cx + r * 1.4), int(cy))
        p.drawLine(int(cx), int(cy - r * 1.4), int(cx), int(cy - r * 0.2))
        p.drawLine(int(cx), int(cy + r * 0.2), int(cx), int(cy + r * 1.4))
        p.fillRect(QRectF(cx - 1.5, cy - 1.5, 3, 3), color)

    def _text(self, p, text, y, color, big=False):
        if not text:
            return
        f = QFont()
        f.setPointSize(26 if big else 16)
        p.setFont(f)
        p.setPen(color)
        p.drawText(QRectF(0, y, self.width(), 80), Qt.AlignHCenter | Qt.AlignTop | Qt.TextWordWrap, text)
