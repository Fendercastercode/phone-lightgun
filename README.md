# RetroSight

This project turns an ARCore phone into an absolute-aim light gun for PC emulators. The
phone can sit in a 3D-printed grip in any orientation. ARCore gives a pose that does not
drift, and the gyro fills in at 200–500 Hz. A calibrated barrel ray is intersected with
the screen, which is anchored as a rectangle in the AR world. The result goes to the PC
over UDP as an absolute mouse position.

```
/android  Kotlin app (Compose, ARCore, OpenCV ArUco, ZXing QR)
/host     Python host: UDP + HMAC, input injection, overlay, calibration solvers
/shared   protocol.md, math.md, test_vectors.json (checked by both sides' tests)
/grip     OpenSCAD grip + barrel profile JSONs (bundled into the APK)
/tools    fake_phone.py, accuracy_harness.py, log_replay.py, gen_test_vectors.py
```

## Quick start

**First time? Do it in this order:**

1. **On the PC:** start the host — double-click `start-host.bat` (Windows) or run
   `./start-host.sh` (Linux, `chmod +x` it first). Leave its window open.
2. In that window, click **Show pairing QR** — a code appears on the TV/monitor.
3. **On the phone:** open RetroSight and tap **Scan QR**, then point the camera at the code.
4. Follow the in-app tutorial on the phone — it walks you through the camera check, measuring
   the TV, and locking on. This only takes a couple of minutes and only needs redoing if you
   move the TV or the phone's camera genuinely loses track of the room.
5. **On the PC:** open RetroArch (or MAME, etc.) and load a light-gun game.
6. Back on the phone, tap **FIX** (or let **Set up the emulator automatically** handle it) if
   the PC window shows the emulator isn't seeing the gun yet.
7. Aim at the TV and pull the trigger.

Everything below is reference detail for each of those steps and for troubleshooting.

**PC host** (Python 3.10+):

```
cd host
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt      # Linux/mac: .venv/bin/pip
.venv\Scripts\python -m lightgun_host
```

Or just double-click `start-host.bat` in the project folder; it sets everything up on first run.

**Don't have RetroArch yet?** Grab it straight from the source instead of your distro's
app store, which is often outdated or missing cores:
* **Windows:** [RetroArch-Win64-setup.exe](https://buildbot.libretro.com/stable/1.22.2/windows/x86_64/RetroArch-Win64-setup.exe) — double-click installer, also upgrades an existing install.
* **Debian / Raspberry Pi OS:** `sudo apt install retroarch` (pulls it from Debian's own repos).
* **Ubuntu:** `sudo add-apt-repository ppa:libretro/stable && sudo apt update && sudo apt install retroarch` (newer than Ubuntu's own package).
* **Any Linux (Flatpak):** `flatpak install flathub org.libretro.RetroArch`.

After installing, use RetroArch's own **Online Updater → Core Downloader** to grab the
cores for the systems you want (see [host/presets.md](host/presets.md) for which cores each
preset expects).

**Linux:** run `./start-host.sh` (first time: `chmod +x start-host.sh`, or `bash start-host.sh`).
On the first run it:
* builds the Python environment, and tells you which package to install if something is missing
  (`python3-venv`, a compiler for `evdev`, `libxcb-cursor0` for the window);
* offers to give RetroSight access to `/dev/uinput`, which it needs to create its virtual light
  guns. It asks for your password once: it adds a udev rule, loads the `uinput` module and puts
  you in the `input` group. It carries on in the same session; after the next log-in the access is
  permanent;
* reminds you to open UDP 47800 if a `ufw` or `firewalld` firewall is on;
* suggests `xdotool` (X11). This lets automatic emulator presets see which emulator is in front
  and give the game its focus back after lock-on. Without it (or on Wayland), the auto preset
  still works when only one emulator is running.

Each phone gets its own virtual light gun device ("Phone Lightgun 1", "Phone Lightgun 2"), so
two players work in RetroArch (udev input driver) and MAME (`lightgunprovider x11`) without
extra drivers. **Fix emulator settings** sets MAME up this way. MAME light guns work best in an
X11 session: on Wayland the host warns you and explains how to switch at the login screen.
With several monitors, the gun is mapped onto the chosen monitor inside the whole desktop.

### Buttons follow the emulator's own settings
When MAME, RetroArch, DuckStation, PCSX2, Dolphin, Supermodel, Flycast or Mesen 2 is in front, the PC program reads that
emulator's settings files, including your own remaps and MAME per-game `cfg/<game>.cfg` files.
It then sends exactly what each function is bound to, and the phone's pad shows those names.
For settings the files don't list, it uses the emulator's built-in defaults. For MAME, those come
from the source code of the installed version (`mame -version` → `src/emu/inpttype.ipp` on GitHub,
downloaded once per version and kept), so a new MAME build with new defaults is picked up.
Offline, it falls back to a built-in copy. You can switch this off with "Read the buttons from the
emulator's own settings".

**Fix emulator settings** keeps your bindings. It only adds a binding where a function (e.g.
Coin 1) has nothing the phone can press. Before anything is saved, every file is copied to
`%APPDATA%\phone-lightgun\backups\<emulator>\<date-time>\` (`~/.config/phone-lightgun/backups`
on Linux), together with a list of the files that were new. The last 20 backups per emulator are
kept. **Undo last fix** puts the newest one back, and a restore can itself be undone.

**Set up the emulator automatically** (on by default) runs this fix by itself whenever the
emulator in front isn't ready for the gun. It also runs when a second phone joins and player 2
needs setting up. Files the emulator saves over when it exits are written as soon as it closes.
The phone and the TV show a short note when this happens. In RetroArch, where the gun is set up
per game, it only does this for games on a built-in list of light gun titles, so normal games are
never changed. For any other game, tap FIX on the phone. Fixers exist for MAME, RetroArch,
DuckStation, PCSX2, Dolphin and Supermodel. Flycast is read but not fixed: it already works with
its defaults. Sega Model 2 and TeknoParrot keep their buttons in per-game files and use fixed presets.

RetroArch, tested with Lethal Enforcers on the FBNeo and MAME cores:
- **Input driver must be `dinput` on Windows.** The `raw` driver only reads real mice and ignores the
  phone: no crosshair, no shots. The fix switches it.
- **FBNeo** lists its "Lightgun" device only once a game is loaded, so the game is loaded briefly
  (no video or sound) to find it. It starts on "Classic" otherwise.
- **The MAME core** has no gun device type. Its `mame_lightgun_mode` option ships as `none` and is
  set to `lightgun`. Because it also reads the RetroPad, Start and Coin come from the RetroPad keys.
  Those changes go into `config/MAME/MAME.cfg`, so the other cores are unaffected.
- **One key, one action:** if a key the phone presses is also bound elsewhere, a press would do two
  things. For example, Right Shift on player 2's Gun Select gave 2 coins per press. The other
  player's binding is cleared. RetroArch hotkeys such as fast-forward on Space are never changed:
  the phone moves to a free key instead.
- Arcade games (short names like `lethalen`) are recognised as gun games by asking your standalone
  MAME (`mame -listxml`).

Pick the game monitor. Enter the screen size, or its diagonal, or let flow A measure it.
Then click **Show pairing QR**. Allow UDP 47800 through the firewall on private networks.

**Phone:** install `android/app/build/outputs/apk/debug/app-debug.apk`. To build it:
`cd android && gradlew assembleDebug` (needs JDK 17 and the Android SDK).

1. **Pair:** scan the QR code once. The key is remembered.
2. **Flow A (once per TV, optional):** hold the phone out of the grip, point the camera
   at the screen showing the ArUco markers, and sweep it slowly. This measures the
   screen's physical size to the millimetre and anchors it.
3. **Flow B (once per grip):** slide the phone into the grip and fire at 5 targets. This
   solves the barrel direction. Printed grips ship with a profile, so this step is just
   a refine.
4. **Every session:** open **Play**. The 4-shot quick re-anchor (flow C) runs on its own.
   After that, play.
5. If you have no camera view of the screen at all, use **flow D**: 18 shots from 2
   standing positions solve screen pose, screen size and barrel together.

In play:
* **Trigger:** volume-down, the trigger touch zone, or a BT button. Each shot gives a
  full-strength kick (length adjustable in Settings).
* **Reload:** shoot off-screen, **flick the gun** (a sharp wrist snap), volume-up, or the
  reload zone. Every reload gives a short click.
* Long-press the middle of the phone screen while aiming at the TV centre to recenter.
* If ARCore loses tracking, the phone switches to gyro-only and blends back when tracking
  returns. If the AR world resets, the gun buzzes and re-runs the 4-shot re-anchor.

Emulator support and setup: see [host/presets.md](host/presets.md). Pick the matching
preset in the host window.

## Testing without hardware

```
cd host && .venv\Scripts\python -m pytest            # solver/protocol/injector tests
cd android && gradlew testDebugUnitTest               # Kotlin math + protocol vs shared vectors
host\.venv\Scripts\python -m lightgun_host --dry-run --port 47999      # no input injection
host\.venv\Scripts\python tools\fake_phone.py --uri "<pairing uri>" --flow A   # then C, D
```

## Design decisions (spec open questions)

* **Body frame:** the barrel is expressed in the Android sensor frame
  (`Frame.getAndroidSensorPose()`). The gyro reports in the same frame, so fusion needs
  no extrinsic. The camera pose is used only for flow A triangulation.
* **Single solver implementation:** every flow is solved on the host
  (`host/lightgun_host/solver.py`). The phone only collects steady poses and ArUco
  observations.
* **Tracking across the hand-off into the grip:** the AR session stays alive, and the
  screen is anchored by an ARCore Anchor. If tracking does not survive on your device,
  run quick re-anchor (flow C) after mounting, which takes about 5 s.
* **1-euro units:** speed is converted to nominal pixels (×3800 px/rad), so the
  spec's defaults (1 Hz, β 0.007) behave as they would on pixel data.

## Status vs build phases

| Phase | State |
|---|---|
| P0 pipeline (ARCore → UDP → cursor) | done |
| P1 flows B + C, overlay, solver | done (verified with synthetic data) |
| P2 gyro fusion, prediction, 1-euro, watchdog, recenter | done |
| P3 flow A (ArUco), flow D | done (verified with synthetic data) |
| P4 buttons, grip profiles, presets, QR pairing + HMAC | done |
| P5 multi-gun (Linux uinput ready; Windows needs ViGEm/driver), USB transport, packaging | not started |

Acceptance numbers (aim error, drift, latency) still have to be measured on real
hardware with `tools/accuracy_harness.py` and a photodiode or high-speed video rig.
