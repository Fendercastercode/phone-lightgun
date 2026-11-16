"""UDP receiver: HMAC check, per-gun sequencing, dispatch to injector / control handler."""
from __future__ import annotations

import logging
import socket
import struct
import threading
import time
from typing import Callable

from . import protocol as P

log = logging.getLogger(__name__)


class GunState:
    __slots__ = ("last_seq", "last_rx", "addr", "count", "rate_hz", "_win_t", "_win_n", "ctrl_seq",
                 "_lost", "_late", "_base", "net")

    def __init__(self):
        self.last_seq = None
        self.last_rx = 0.0
        self.addr = None
        self.count = 0
        self.rate_hz = 0.0
        self._win_t = time.monotonic()
        self._win_n = 0
        self.ctrl_seq = 0
        self._lost = 0             # aim packets missing in this window (seq gaps)
        self._late = []            # extra delay of each packet vs the best seen (s)
        self._base = None          # smallest (host arrival - phone timestamp) = the no-queueing delay
        self.net = {"loss": 0.0, "late_ms": 0.0}   # last 2 s window, sent to the phone in every pong

    def track(self, seq: int, phone_time_ns: int, now: float):
        """Packet loss and Wi-Fi delay spikes, from sequence gaps and the phone's timestamps."""
        if self.last_seq is not None and now - self.last_rx < 1.0:
            gap = ((seq - self.last_seq) & 0xFFFFFFFF) - 1
            if 0 < gap < 1000:
                self._lost += gap
        d = now - phone_time_ns / 1e9      # constant clock offset + network delay
        if self._base is None or d < self._base:
            self._base = d
        self._late.append(d - self._base)


class Receiver:
    """Runs on its own thread. `on_aim` is called on that thread (keep it fast);
    `on_ctrl(gun_id, msg, addr)` likewise - marshal to the UI thread yourself."""

    def __init__(self, key: bytes, port: int, on_aim: Callable[[P.AimPacket], None],
                 on_ctrl: Callable[[int, dict, tuple], None], bind: str = "0.0.0.0",
                 log_file=None):
        self.key = key
        self.on_aim = on_aim
        self.on_ctrl = on_ctrl
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        self.sock.bind((bind, port))
        self.sock.settimeout(0.5)
        self.port = self.sock.getsockname()[1]
        self.guns: dict[int, GunState] = {}
        self.bad_mac = 0
        self.dropped = 0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="udp-rx", daemon=True)
        self._log = log_file  # binary log: <d t_host><H len><bytes>
        self.probe_info: Callable[[], dict] | None = None  # extra fields for probe_ack

    def start(self):
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        self._thread.join(timeout=2)
        self.sock.close()

    def gun(self, gid: int) -> GunState:
        g = self.guns.get(gid)
        if g is None:
            g = self.guns[gid] = GunState()
        return g

    def send_ctrl(self, gun_id: int, msg: dict, addr=None):
        g = self.gun(gun_id)
        addr = addr or g.addr
        if addr is None:
            return
        g.ctrl_seq += 1
        try:
            self.sock.sendto(P.encode_ctrl(self.key, gun_id, g.ctrl_seq, msg), addr)
        except OSError as e:
            log.warning("send_ctrl failed: %s", e)

    def _run(self):
        while not self._stop.is_set():
            try:
                data, addr = self.sock.recvfrom(P.MAX_CTRL)
            except socket.timeout:
                continue
            except OSError:
                if self._stop.is_set():
                    break
                continue  # Windows: ICMP port unreachable surfaces as recv error
            now = time.monotonic()
            if self._log is not None:
                self._log.write(struct.pack("<dH", time.time(), len(data)) + data)
            magic = P.peek_magic(data)
            if magic == P.AIM_MAGIC:
                pkt = P.decode_aim(self.key, data)
                if pkt is None:
                    self.bad_mac += 1
                    continue
                g = self.gun(pkt.gun_id)
                if g.last_seq is not None and now - g.last_rx < 1.0 and not P.seq_newer(pkt.seq, g.last_seq):
                    self.dropped += 1
                    continue
                g.track(pkt.seq, pkt.phone_time_ns, now)
                g.last_seq, g.last_rx, g.addr = pkt.seq, now, addr
                g.count += 1
                g._win_n += 1
                if now - g._win_t >= 2.0:
                    g.rate_hz = g._win_n / (now - g._win_t)
                    late = sorted(g._late)
                    p95 = late[int(len(late) * 0.95)] if late else 0.0
                    g.net = {"loss": round(100.0 * g._lost / max(g._lost + g._win_n, 1), 1),
                             "late_ms": round(p95 * 1000.0, 1)}
                    # let the baseline creep up a little so clock drift can't fake "late"
                    if late and g._base is not None:
                        g._base += min(late[0], 0.002)
                    g._win_t, g._win_n, g._lost, g._late = now, 0, 0, []
                try:
                    self.on_aim(pkt)
                except Exception:  # never let injection kill the receiver
                    log.exception("on_aim failed")
            elif magic == P.CTRL_MAGIC:
                dec = P.decode_ctrl(self.key, data)
                if dec is None:
                    self.bad_mac += 1
                    continue
                gid, _seq, msg = dec
                if msg["type"] == "probe":
                    # "are you there?" from a phone's PC list: answer, but don't treat it as the
                    # gun's live connection (no addr/active-gun change, no UI side effects)
                    info = {"type": "probe_ack", "t": msg.get("t")}
                    if self.probe_info:
                        try:
                            info.update(self.probe_info())
                        except Exception:
                            log.exception("probe_info failed")
                    self.ctrl_seq_probe = getattr(self, "ctrl_seq_probe", 0) + 1
                    self.sock.sendto(P.encode_ctrl(self.key, gid, self.ctrl_seq_probe, info), addr)
                    continue
                g = self.gun(gid)
                g.addr = addr
                if msg["type"] == "ping":
                    self.send_ctrl(gid, {"type": "pong", "t": msg.get("t"), "net": g.net}, addr)
                try:
                    self.on_ctrl(gid, msg, addr)
                except Exception:
                    log.exception("on_ctrl failed")


def local_ips() -> list[str]:
    ips = []
    try:  # primary outbound interface; connect() on UDP sends nothing
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    def rank(ip):
        if ip.startswith("192.168."):
            return 0
        if ip.startswith("10."):
            return 1
        if ip.startswith("172."):   # also used by WSL/Docker/VPN adapters
            return 2
        return 3
    ips.sort(key=rank)
    return ips or ["127.0.0.1"]
