"""Desktop host skin: the mobile app's 90s-arcade palette
(android/app/src/main/java/dev/lightgun/ui/Retro.kt's Neon object), applied to
the native Qt controls. Skin only - no animation, that's for the phone screen.
"""
from __future__ import annotations

from PySide6.QtGui import QFont
from PySide6.QtWidgets import QApplication

NIGHT = "#0D0221"
DEEP = "#1A0640"
PANEL = "#241056"
MAGENTA = "#FF2BD6"
CYAN = "#00F0FF"
YELLOW = "#FFE600"
LIME = "#39FF14"
ORANGE = "#FF7A00"
RED = "#FF2244"
WHITE = "#F4F1FF"
DIM = "#9C8CD9"

_STYLESHEET = f"""
QWidget {{
    background-color: {NIGHT};
    color: {WHITE};
    font-weight: bold;
}}
QGroupBox {{
    background-color: {PANEL};
    border: 2px solid {CYAN};
    border-top-color: #66f8ff;
    border-left-color: #66f8ff;
    border-bottom-color: #008a94;
    border-right-color: #008a94;
    border-radius: 0px;
    margin-top: 14px;
    padding-top: 10px;
}}
QGroupBox::title {{
    subcontrol-origin: margin;
    subcontrol-position: top left;
    left: 10px;
    padding: 1px 6px;
    background-color: {CYAN};
    color: {NIGHT};
}}
QPushButton {{
    background-color: {DEEP};
    color: {CYAN};
    border: 2px solid {CYAN};
    border-top-color: #66f8ff;
    border-left-color: #66f8ff;
    border-bottom-color: #008a94;
    border-right-color: #008a94;
    padding: 6px 14px;
}}
QPushButton:hover {{
    border-color: {YELLOW};
    color: {YELLOW};
}}
QPushButton:pressed {{
    border-top-color: #008a94;
    border-left-color: #008a94;
    border-bottom-color: #66f8ff;
    border-right-color: #66f8ff;
}}
QPushButton:checked {{
    background-color: {MAGENTA};
    color: {NIGHT};
    border-color: {MAGENTA};
}}
QPushButton:disabled {{
    color: {DIM};
    border-color: {DIM};
}}
QComboBox, QDoubleSpinBox, QPlainTextEdit {{
    background-color: {DEEP};
    color: {WHITE};
    border: 2px solid {CYAN};
    padding: 3px;
}}
QComboBox QAbstractItemView {{
    background-color: {DEEP};
    color: {WHITE};
    selection-background-color: {MAGENTA};
    selection-color: {NIGHT};
}}
QCheckBox {{
    spacing: 8px;
}}
QCheckBox::indicator {{
    width: 14px;
    height: 14px;
    background-color: {DEEP};
    border: 2px solid {CYAN};
}}
QCheckBox::indicator:checked {{
    background-color: {LIME};
    border-color: {LIME};
}}
QLabel {{
    background-color: transparent;
}}
"""


def apply_theme(app: QApplication) -> None:
    font = QFont("Consolas")
    if not QFont(font).exactMatch():
        font = QFont("Courier New")
    font.setBold(True)
    font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, 1)
    app.setFont(font)
    app.setStyleSheet(_STYLESHEET)
