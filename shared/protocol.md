# Wire protocol (v1)

All traffic is UDP between the phone and the PC host (default port **47800**).
All integers and floats are **little-endian**. Every packet ends with a 4-byte
truncated HMAC: the first 4 bytes of `HMAC-SHA256(key, packet_without_hmac)`.
`key` is the 128-bit session key from QR pairing.

Two packet families share the socket and are told apart by the first two bytes.

## 1. Aim packet — magic `0x4C47` ("GL" on the wire), exactly 32 bytes

| off | type | field | notes |
|----:|------|-------|-------|
| 0  | u16 | magic | `0x4C47` |
| 2  | u8  | version | `1` |
| 3  | u8  | gunId | 0..255 |
| 4  | u32 | seq | increments per aim packet, wraps |
| 8  | u64 | phoneTimeNs | `elapsedRealtimeNanos` of the fused sample |
| 16 | f32 | x | normalized 0..1, clamped |
| 20 | f32 | y | normalized 0..1, clamped |
| 24 | u8  | flags | bit0 onScreen, bit1 trackingOK, bit2 gyroOnly, bit3 calibrated |
| 25 | u8  | presses | low nibble: trigger presses mod 16, high nibble: reload presses mod 16 (0 from old phones) |
| 26 | u16 | buttons | bit0 trigger, bit1 reload, bit2 A, bit3 B, bit4 start, bit5 select, bit6 recenter-held |
| 28 | u8[4] | hmac32 | HMAC over bytes 0..27 |

Phone sends at ~200 Hz (or every fused sample). Host keeps, per gunId, the last
accepted `seq` and drops packets with `seq` not newer (serial-number arithmetic,
`(int32)(seq - last) > 0`). If no packet arrived for 1 s the sequence state is
reset (phone restarted). **Last packet wins**; no jitter buffer beyond that.

**Robustness.** A press that falls entirely inside lost packets still advances
`presses`: the host replays up to 2 missed presses as a 50 ms click, but only if the
gap was under 0.5 s. The host releases held buttons for a gun that has been silent for
0.3 s. The host also measures loss (seq gaps) and extra delay (arrival minus
`phoneTimeNs`, relative to the best seen) over 2 s windows. It returns them in every
`pong` as `net:{loss (%), late_ms (p95)}`.

If `calibrated` is clear the x/y values are meaningless and the host must not
move the cursor (buttons are still forwarded).

## 2. Control packet — magic `0x4C43` ("CL" on the wire), variable length

| off | type | field |
|----:|------|-------|
| 0 | u16 | magic `0x4C43` |
| 2 | u8  | version `1` |
| 3 | u8  | gunId |
| 4 | u32 | seq (independent counter) |
| 8 | …   | UTF-8 JSON object, must contain `"type"` |
| end-4 | u8[4] | hmac32 over everything before |

Max size 8 KiB. Control messages are not retransmitted by the transport; the
calibration flows are written so that a lost message is recovered by the user
firing again (the host re-sends the current target on every `shot` it rejects).

### Phone → host

| type | body | meaning |
|------|------|---------|
| `hello` | `{device, app_version}` | sent on connect and every 5 s until `hello_ack` |
| `ping` | `{t, stats:{rate_hz, tracking, gyro_only, battery}}` | 1 Hz keepalive |
| `calib_request` | `{flow:"A"\|"B"\|"C"\|"D", barrel:{b:[3], t:[3]}, fit_t:bool}` | start a flow; `barrel` = current grip profile |
| `shot` | `{index, q:[x,y,z,w], p:[3], n, w_rms}` | averaged phone pose (ARCore world, Android-sensor body frame) while aiming at target `index` |
| `aruco_obs` | `{K:[fx,fy,cx,cy], size:[w,h], q:[4], p:[3], markers:{id:[8 floats]}}` | flow A: one camera view; `q,p` = ARCore `Camera.getPose()` (OpenGL camera convention), corners in CPU-image pixels, order TL,TR,BR,BL of the marker |
| `calib_cancel` | `{}` | abort the running flow |

### Host → phone

| type | body | meaning |
|------|------|---------|
| `hello_ack` | `{host, display:{id, w_px, h_px}, screen_size_m:[w,h]\|null}` | |
| `pong` | `{t, net:{loss, late_ms}}` | echo of `ping.t` for RTT + the host's view of the aim stream |
| `calib_target` | `{flow, index, total, x, y, retry}` | aim at normalized (x,y) and fire |
| `calib_progress` | `{flow, fraction, views, message}` | flow A sweep progress |
| `calib_result` | `{flow, ok, rms_px, rms_mm?, screen?:{s0,u,v,w_m,h_m}, barrel?:{b,t}, message}` | screen is in the phone's **current ARCore world** frame |
| `calib_error` | `{flow, message}` | |

The phone, on `calib_result` with a `screen`, creates an ARCore Anchor at the
screen centre with orientation `[u v u×v]` and from then on recomputes the world
screen from `anchor.getPose()` every frame.

## Pairing

The host overlay shows a QR code with

```
lightgun://<ip>:<port>?k=<32 hex chars>&v=1&id=<gunId>
```

The key persists on both sides, so pairing is done once per host.

## Transports (later)

* USB: `adb reverse udp:47800 udp:47800` is not supported by adb for UDP, so the
  USB transport will tunnel the same packets over TCP with a 2-byte length prefix.
* WebRTC DataChannel (unordered, maxRetransmits=0) carrying identical payloads.
