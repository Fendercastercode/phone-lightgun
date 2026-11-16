# Math notes

## Frames

* `W` — ARCore world. Gravity aligned, +Y up. Resets every ARCore session.
* `P` — phone body = **Android sensor frame** (`Frame.getAndroidSensorPose()`):
  +X right, +Y up along the portrait long edge, +Z out of the display. This is the
  same frame the gyroscope reports in, so fusion needs no extrinsic. **Decision
  for v1: the barrel is always expressed in this frame.** (The camera pose is
  only used for flow A triangulation.)
* `C` — ARCore camera (`Camera.getPose()`), OpenGL convention: +X right, +Y up,
  looking down −Z, in the sensor's natural (landscape) orientation, matching the
  CPU image and `getImageIntrinsics()`. OpenCV camera = `R_WC · diag(1,−1,−1)`.
* `S` — screen: origin at top-left corner `S0`, +x = `u` (right), +y = `v` (down),
  +z = `n = u × v` (into the screen, away from the player).

Quaternions are `[x, y, z, w]` (ARCore order), Hamilton convention,
`v_W = q ⊗ v_P ⊗ q*`.

## Ray

Barrel: unit direction `b_P`, origin offset `t_P` (m). Phone pose `R_WP, p_W`.

```
o = p_W + R_WP · t_P
d = R_WP · b_P
```

Default barrel when there is no grip profile: back-camera axis, `b_P = (0,0,−1)`, `t_P = 0`.

## Ray–screen intersection

```
n = u × v
s = n·(S0 − o) / (n·d)        require |n·d| > 1e-9 and s > 0
h = o + s·d
x = u·(h − S0) / Wm
y = v·(h − S0) / Hm
onScreen = 0 ≤ x ≤ 1 and 0 ≤ y ≤ 1
```

The packet carries x,y clamped to [0,1] plus the `onScreen` flag. If the ray is
parallel or points away, `onScreen = false` and the last valid point is held.

## Anchor-relative screen

Anchor pose `A` (centre of the screen, rotation columns `[u v n]`). Per frame:

```
c  = A.translation + u·δu + v·δv      (δu,δv = recenter offset, metres)
u  = A.rotate(1,0,0),  v = A.rotate(0,1,0)
S0 = c − u·Wm/2 − v·Hm/2
```

## Calibration solvers (host, `host/lightgun_host/solver.py`)

All solvers minimise **on-screen pixel error**: for each shot `k` at target
`(x_k, y_k)` the residual is `((x̂_k − x_k)·Wpx, (ŷ_k − y_k)·Hpx)` where
`(x̂, ŷ)` is the ray–plane hit above. Optimiser: `scipy.optimize.least_squares`
(LM, falling back to TRF if under-determined).

### Flow C — screen pose, known size and barrel (6 DOF)

Initialisation is planar PnP with bearing vectors. Put the origin at the mean
ray origin `c`. Each target point is `P_k − c = H·[X_k, Y_k, 1]ᵀ` with
`H = [u v S0−c]` and `X_k = x_k·Wm, Y_k = y_k·Hm`. The bearing `d_k ∥ H·x_k`, so

```
[d_k]× (I₃ ⊗ x_kᵀ) · vec_row(H) = 0
```

Stack, take the SVD null vector, scale by `λ = 2/(|h1|+|h2|)`, flip sign so
`d_k·(H x_k) > 0`, orthonormalise `[h1 h2 h1×h2]` with SVD. Then LM over
(rotation vector, S0) using the true per-shot origins. Four shots minimum; a
fifth (centre) is requested if RMS > 1% of screen width.

### Flow B — barrel (2 DOF direction + optional 3 DOF origin)

`d_k = normalize(R_kᵀ (P_k − p_k − R_k t_P))`, init `b = normalize(Σ d_k)`.
Direction parameterised in the tangent plane of the initial guess:
`b = normalize(b0 + α e1 + β e2)`. `t_P` is only fitted when shots come from
≥ 2 standing positions (spread > 0.5 m), otherwise it stays at the grip CAD value.

### Flow D — joint (screen 6 + width 1 + barrel 2[+3])

Height is tied to width by the display pixel aspect (square pixels). Init: screen
centre 2 m along the mean initial ray, `v = −Y_world`, `u = v × (−d̄)`
horizontalised, width 1.2 m. 9 targets × 2 positions → 36 residuals.

### Flow A — exact physical size from ArUco markers

1. Each `(marker id, corner)` is triangulated by linear DLT across all views
   (OpenCV-convention projection matrices `K [R_CW | −R_CW p]`), views with
   reprojection > 4 px dropped.
2. The host knows every corner's pixel position `(px, py)` on the display.
   Umeyama similarity between `(px, py, 0)` and the triangulated points gives
   `S0, R, m` (metres per pixel).
3. LM over (rotvec, S0, log m) on raw reprojection error across all views.
4. `Wm = m·Wpx`, `Hm = m·Hpx`. Accept if 3-D RMS < 3 mm.

## Pose fusion (phone)

* Gyro integrated at sensor rate into `q_G` (arbitrary frame G), ring buffer 1 s.
* ARCore frame at `t_a` with `q_A`: `q_err = q_A ⊗ q_G(t_a)*`;
  `q_off ← slerp(q_off, q_err, k)`, `k ≈ 0.1`. Output `q = q_off ⊗ q_G(now)`.
  On recovery after tracking loss `q_off` snaps (`k = 1`).
* Position: ARCore only, linear extrapolation ≤ 50 ms.
* Prediction: `q_pred = q ⊗ exp(ω_P · (now − t_gyro + Δ)/2)`, Δ default 16 ms.
* Smoothing: 1-euro on the ray direction. The speed term is converted to
  "nominal pixels" with `pxPerRad = 3800` (≈1920 px across 29°) so the classic
  defaults (min_cutoff 1 Hz, β 0.007) behave as intended.
* Timestamps: `SensorEvent.timestamp` is `elapsedRealtimeNanos`. ARCore's frame
  timestamp base is unspecified; it is mapped to `elapsedRealtimeNanos` if it is
  within 1 s of it, else treated as `System.nanoTime()` and offset.

## Recenter

Aim at the screen centre and long-press. With hit `h` and centre `c`, the
smallest rigid correction that puts the hit at (0.5, 0.5) is the in-plane
translation `δ = h − c` (2 DOF, 2 constraints; a yaw term adds nothing for one
point). `(δu, δv) += (u·δ, v·δ)`, blended in over 200 ms.
