# Grip

`lightgun_grip.scad` is a parametric pistol grip. Enter your phone's dimensions (with
case), camera position and volume-button position, pick `mount = "top"` or `"side"`,
and render:

```
openscad -o grip.stl -D 'mount="top"' lightgun_grip.scad
```

The phone's **top edge points toward the muzzle**, so the nominal barrel direction is
`b_P = (0, 1, 0)` in the Android sensor frame for both mounts. OpenSCAD echoes a
`PROFILE {...}` line with the matching JSON; save it under `assets/grip_profiles/`
(bundled into the APK) and select it in the app's Settings. With a CAD profile, flow B
only needs to refine the direction (5 shots), and `t_P` stays fixed.

Rules of thumb:

* Keep the camera window fully open. ARCore keeps tracking from whatever it sees, so
  a floor-facing (`top`) or room-facing (`side`) camera works, but a blank ceiling does not.
* The lever is a print-in-place flexure over volume-down (the app's trigger). A BLE
  camera-shutter button taped to the handle also works (it sends volume-up/ENTER,
  mapped to reload/trigger).
* Print in PETG, 4 walls, with the cradle floor on the bed. The mount style changes only
  which way the cradle is rolled relative to the handle.
