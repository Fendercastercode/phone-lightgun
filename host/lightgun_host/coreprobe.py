"""Ask a libretro core which controller types it offers on each port.

Run as a separate process (python -m lightgun_host.coreprobe <core.dll> [game]) so a misbehaving
core can't take the host down. Prints PORTS=[[{"desc": "MD Menacer", "id": 516}, ...], ...].

Most cores say this as soon as they start. Arcade cores (FinalBurn Neo) only know their controllers
once a game is loaded, so with [game] the game is loaded too (no video or sound, nothing runs).
"""
from __future__ import annotations

import ctypes
import json
import os
import sys

GET_SYSTEM_DIRECTORY, GET_SAVE_DIRECTORY = 9, 31
SET_CONTROLLER_INFO = 35


class _Desc(ctypes.Structure):
    _fields_ = [("desc", ctypes.c_char_p), ("id", ctypes.c_uint)]


class _Info(ctypes.Structure):
    _fields_ = [("types", ctypes.POINTER(_Desc)), ("num_types", ctypes.c_uint)]


class _GameInfo(ctypes.Structure):
    _fields_ = [("path", ctypes.c_char_p), ("data", ctypes.c_void_p), ("size", ctypes.c_size_t),
                ("meta", ctypes.c_char_p)]


_keep: list = []


def probe(path: str, game: str | None = None) -> list:
    d = os.path.dirname(os.path.abspath(path))
    if hasattr(os, "add_dll_directory"):
        for p in (d, os.path.dirname(d)):
            try:
                os.add_dll_directory(p)
            except OSError:
                pass
    lib = ctypes.CDLL(path)
    ports: list = []
    sysdir = ctypes.c_char_p(os.path.dirname(os.path.abspath(game or path)).encode())
    ENV = ctypes.CFUNCTYPE(ctypes.c_bool, ctypes.c_uint, ctypes.c_void_p)

    def env(cmd, data):
        cmd &= 0xFFFF
        if cmd == SET_CONTROLLER_INFO and data:
            arr = ctypes.cast(data, ctypes.POINTER(_Info))
            found = []
            i = 0
            while arr[i].types and i < 16:
                found.append([{"desc": (arr[i].types[k].desc or b"").decode("utf-8", "replace"),
                               "id": arr[i].types[k].id} for k in range(arr[i].num_types)])
                i += 1
            ports[:] = found              # the game's own list replaces the core's generic one
            return True
        if game and cmd in (GET_SYSTEM_DIRECTORY, GET_SAVE_DIRECTORY) and data:
            ctypes.cast(data, ctypes.POINTER(ctypes.c_char_p))[0] = sysdir.value
            return True
        return False

    cb = ENV(env)
    _keep.append(cb)
    lib.retro_set_environment(cb)
    if hasattr(lib, "retro_init"):
        lib.retro_init()
    if game and not any(t["id"] & 0xFF == 4 for p in ports for t in p):
        for name, proto in (
                ("retro_set_video_refresh", ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint,
                                                             ctypes.c_size_t)),
                ("retro_set_audio_sample", ctypes.CFUNCTYPE(None, ctypes.c_int16, ctypes.c_int16)),
                ("retro_set_audio_sample_batch", ctypes.CFUNCTYPE(ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t)),
                ("retro_set_input_poll", ctypes.CFUNCTYPE(None)),
                ("retro_set_input_state", ctypes.CFUNCTYPE(ctypes.c_int16, ctypes.c_uint, ctypes.c_uint,
                                                           ctypes.c_uint, ctypes.c_uint))):
            f = proto(lambda *a: 0)
            _keep.append(f)
            getattr(lib, name)(f)
        gi = _GameInfo(os.path.abspath(game).encode(), None, 0, None)
        lib.retro_load_game(ctypes.byref(gi))
    return ports


if __name__ == "__main__":
    result = json.dumps(probe(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))
    sys.stdout.write("\nPORTS=" + result + "\n")  # marker line: cores may print their own noise
    sys.stdout.flush()
    os._exit(0)  # skip core destructors
