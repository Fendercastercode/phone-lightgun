"""Packet encode/decode. See shared/protocol.md."""
from __future__ import annotations

import hashlib
import hmac
import json
import struct
from dataclasses import dataclass

AIM_MAGIC = 0x4C47
CTRL_MAGIC = 0x4C43
VERSION = 1
AIM_SIZE = 32
MAX_CTRL = 8192

FLAG_ON_SCREEN = 1 << 0
FLAG_TRACKING = 1 << 1
FLAG_GYRO_ONLY = 1 << 2
FLAG_CALIBRATED = 1 << 3

BTN_TRIGGER = 1 << 0
BTN_RELOAD = 1 << 1
BTN_A = 1 << 2
BTN_B = 1 << 3
BTN_START = 1 << 4
BTN_SELECT = 1 << 5
BTN_RECENTER = 1 << 6

BUTTON_NAMES = {
    "trigger": BTN_TRIGGER, "reload": BTN_RELOAD, "a": BTN_A, "b": BTN_B,
    "start": BTN_START, "select": BTN_SELECT,
}

_AIM_HEAD = struct.Struct("<HBBIQffBBH")  # 28 bytes
_CTRL_HEAD = struct.Struct("<HBBI")       # 8 bytes
assert _AIM_HEAD.size == 28


def mac32(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()[:4]


@dataclass
class AimPacket:
    gun_id: int
    seq: int
    phone_time_ns: int
    x: float
    y: float
    flags: int
    buttons: int
    presses: int = 0   # low nibble: trigger presses (mod 16), high nibble: reload presses

    @property
    def on_screen(self):
        return bool(self.flags & FLAG_ON_SCREEN)

    @property
    def calibrated(self):
        return bool(self.flags & FLAG_CALIBRATED)


def encode_aim(key: bytes, p: AimPacket) -> bytes:
    body = _AIM_HEAD.pack(AIM_MAGIC, VERSION, p.gun_id, p.seq & 0xFFFFFFFF,
                          p.phone_time_ns & 0xFFFFFFFFFFFFFFFF, p.x, p.y,
                          p.flags, p.presses & 0xFF, p.buttons)
    return body + mac32(key, body)


def decode_aim(key: bytes, data: bytes) -> AimPacket | None:
    if len(data) != AIM_SIZE:
        return None
    body, tag = data[:28], data[28:]
    if not hmac.compare_digest(mac32(key, body), tag):
        return None
    magic, ver, gun, seq, t, x, y, flags, presses, buttons = _AIM_HEAD.unpack(body)
    if magic != AIM_MAGIC or ver != VERSION:
        return None
    return AimPacket(gun, seq, t, x, y, flags, buttons, presses)


def encode_ctrl(key: bytes, gun_id: int, seq: int, msg: dict) -> bytes:
    body = _CTRL_HEAD.pack(CTRL_MAGIC, VERSION, gun_id, seq & 0xFFFFFFFF)
    body += json.dumps(msg, separators=(",", ":")).encode()
    if len(body) + 4 > MAX_CTRL:
        raise ValueError("control message too large")
    return body + mac32(key, body)


def decode_ctrl(key: bytes, data: bytes):
    """Returns (gun_id, seq, msg) or None."""
    if len(data) < 12 or len(data) > MAX_CTRL:
        return None
    body, tag = data[:-4], data[-4:]
    if not hmac.compare_digest(mac32(key, body), tag):
        return None
    magic, ver, gun, seq = _CTRL_HEAD.unpack(body[:8])
    if magic != CTRL_MAGIC or ver != VERSION:
        return None
    try:
        msg = json.loads(body[8:].decode())
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(msg, dict) or "type" not in msg:
        return None
    return gun, seq, msg


def peek_magic(data: bytes) -> int:
    return struct.unpack_from("<H", data, 0)[0] if len(data) >= 2 else 0


def seq_newer(seq: int, last: int) -> bool:
    d = (seq - last) & 0xFFFFFFFF
    return 0 < d < 0x80000000


def pairing_uri(ip: str, port: int, key: bytes, gun_id: int = 0) -> str:
    return f"lightgun://{ip}:{port}?k={key.hex()}&v={VERSION}&id={gun_id}"
