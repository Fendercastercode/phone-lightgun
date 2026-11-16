"""Inspect or replay a raw packet log written by `python -m lightgun_host --log file.lglog`.

    python tools/log_replay.py stats file.lglog          # rate, jitter, gaps, phone->host clock skew
    python tools/log_replay.py replay file.lglog --to 127.0.0.1:47800   # resend with original timing
"""
import argparse
import socket
import struct
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "host"))
from lightgun_host import protocol as P  # noqa: E402

HEAD = struct.Struct("<dH")


def read(path):
    data = Path(path).read_bytes()
    i = 0
    while i + HEAD.size <= len(data):
        t, n = HEAD.unpack_from(data, i)
        i += HEAD.size
        yield t, data[i:i + n]
        i += n


def stats(path):
    ts, phone, seqs, ctrl = [], [], [], 0
    for t, pkt in read(path):
        if P.peek_magic(pkt) == P.AIM_MAGIC and len(pkt) == P.AIM_SIZE:
            _m, _v, _g, seq, pt = struct.unpack_from("<HBBIQ", pkt)
            ts.append(t); phone.append(pt * 1e-9); seqs.append(seq)
        else:
            ctrl += 1
    if len(ts) < 2:
        print("no aim packets"); return
    ts, phone, seqs = np.array(ts), np.array(phone), np.array(seqs, dtype=np.int64)
    dt = np.diff(ts) * 1000
    lost = int(np.sum(np.clip(np.diff(seqs) - 1, 0, None)))
    skew = (ts - ts[0]) - (phone - phone[0])  # arrival delay variation
    print(f"aim packets {len(ts)}  control {ctrl}  duration {ts[-1] - ts[0]:.1f}s")
    print(f"rate {len(ts) / (ts[-1] - ts[0]):.1f} Hz  interval median {np.median(dt):.2f} ms  "
          f"p99 {np.percentile(dt, 99):.2f} ms  max {dt.max():.1f} ms")
    print(f"lost (seq gaps) {lost}  ({100 * lost / (lost + len(ts)):.2f}%)")
    print(f"arrival jitter (vs phone clock) p50 {np.percentile(skew - skew.min(), 50) * 1000:.2f} ms  "
          f"p99 {np.percentile(skew - skew.min(), 99) * 1000:.2f} ms")


def replay(path, to):
    host, port = to.rsplit(":", 1)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    t0 = w0 = None
    for t, pkt in read(path):
        if t0 is None:
            t0, w0 = t, time.perf_counter()
        while time.perf_counter() - w0 < t - t0:
            pass
        s.sendto(pkt, (host, int(port)))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["stats", "replay"])
    ap.add_argument("log")
    ap.add_argument("--to", default="127.0.0.1:47800")
    a = ap.parse_args()
    stats(a.log) if a.cmd == "stats" else replay(a.log, a.to)
