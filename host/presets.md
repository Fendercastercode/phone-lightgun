# Emulator & platform compatibility

## How the gun reaches the game

The host can present the phone to the PC in three ways (pick in the **Output** box):

| Output | What the PC sees | Works with |
|---|---|---|
| **mouse** (default) | The system cursor moves in absolute coordinates, with clicks and keys | Almost every emulator with mouse/light-gun support, plus PC shooters |
| **gamepad** | A virtual Xbox 360 pad per gun (Windows: ViGEm). Linux: a uinput pad. Aim goes on the right stick | Emulators that take a pointer from a stick (Dolphin IR, some PCSX2/RPCS3 setups), and multi-gun on Windows |
| **both** | Both at once | Front-ends that read one input and games that read the other |

Linux mouse output creates one uinput absolute device per gun ("Phone Lightgun N"), so
multiple guns work natively in RetroArch (udev), MAME (evdev) and Batocera-style setups.

Reload is decided on the phone, as either **shoot off-screen** or **flick**. Both arrive
as the *reload* button, and the preset says what reload means to each emulator:

| Reload style | Action | Used by |
|---|---|---|
| Separate button | `mouse:right` / `key:r` | RetroArch, Flycast, DuckStation ("Shoot Offscreen" bind), PCSX2, TeknoParrot |
| Real off-screen shot | `offscreen:left` (cursor jumps to the corner and fires) | MAME `offscreen_reload`, games that check the gun position |
| Gamepad button | `pad:lt` | virtual-gamepad preset |

## Which emulator, and which gun to pick

### RetroArch: core vs device type
A **core** is the emulator for one console inside RetroArch (e.g. Snes9x = SNES). In each
core you also choose the **device type**: what is plugged into the emulated console's
controller port (Quick Menu → Controls → Port 1 Controls → Device Type).

| Device type | What the emulated console thinks is plugged in | Use it for |
|---|---|---|
| **RetroPad** (default) | A normal controller | Normal games. Gun games ignore your aim. |
| **Mouse** (SNES Mouse, PS Mouse…) | A console mouse, which reports *movement* instead of position | Mouse games (e.g. Mario Paint). **Wrong for gun games:** the aim drifts and won't line up. |
| **Light gun** (Zapper, Super Scope, GunCon, Menacer, Justifier…) | A real light gun | **Every gun game.** RetroArch tells the game "the gun points here" based on where the cursor is. |

A game only reacts to the gun it was made for (Duck Hunt reads only a Zapper, Time Crisis
only a GunCon). If the trigger or aim does nothing, the device type is wrong. Nothing
breaks; just switch it.

### Which emulator for which system
| System | Best choice | Gun device to pick |
|---|---|---|
| NES | RetroArch: **Mesen** or FCEUmm | Zapper |
| SNES | RetroArch: **Snes9x** | Super Scope, or Justifier (Lethal Enforcers) |
| Master System | RetroArch: **Genesis Plus GX** | MS Light Phaser |
| Genesis / Mega Drive, Sega CD | RetroArch: **Genesis Plus GX** | **Port 2**: MD Menacer, or MD Justifiers (Konami games) |
| PS1 | RetroArch: **Beetle PSX**, or standalone **DuckStation** | GunCon (Namco games), or Justifier (Konami games) |
| Saturn | RetroArch: Beetle Saturn | Virtua Gun |
| Dreamcast / Naomi | **Flycast** (core or standalone) | Light Gun |
| PS2 | Standalone **PCSX2** (no good RetroArch core) | GunCon2. Run the game's own calibration once. |
| Wii | Standalone **Dolphin** | Wii Remote, Point = cursor |
| Arcade (2D, early 3D) | **MAME** (core or standalone) | `lightgun 1`, `lightgunprovider win32` |
| Sega Model 2 / Model 3 | Model 2 Emulator / **Supermodel** | Gun via mouse / raw input |
| Modern arcade | **TeknoParrot** | Gun bound to the mouse |

**Rule of thumb:** use RetroArch for 8-bit, 16-bit and PS1. Use the standalone emulators
for PS2, Wii and the 3D arcade boards.

### Sega guns: Light Phaser vs Menacer vs Justifier
All three appear in Genesis Plus GX. Pick the one the game was made for:

| Gun | Console | Real hardware | Example games |
|---|---|---|---|
| **Light Phaser** | Master System (8-bit) | Small pistol on a cable | Safari Hunt, Shooting Gallery, Rescue Mission, Gangster Town, Operation Wolf |
| **Menacer** | Genesis / Mega Drive, Sega CD (16-bit) | Wireless bazooka-style gun with stock and scope | Menacer 6-Game Cartridge, T2: The Arcade Game, Body Count |
| **Justifier** | Genesis, Sega CD (and SNES, PS1) | Konami's own pistol | Lethal Enforcers I & II, Snatcher |

Master System game → **port 1** = MS Light Phaser. Genesis/Sega CD game → **port 2** =
MD Menacer, or MD Justifiers for the Konami games (on the real console these guns plug into
the second controller socket). Not sure? Try Menacer first, then Justifiers.

**Make RetroArch remember it.** RetroArch forgets the device type when the game closes
unless you save it: pick the device, then Quick Menu → Controls → Manage Remap Files →
**Save Game Remap File**. For a port-2 gun also set Settings → Input → Port 2 Controls →
**Mouse Index** to the same mouse as port 1 (usually 0), and bind Port 2's Gun Trigger = mouse 1,
Gun Start = Enter, Gun Reload/Offscreen = mouse 2 - port 2 starts with no Start button, so the
game never gets past the title screen.
Turn on the core option "Show Lightgun Crosshair" to see where you aim.

### When plain mouse is enough
- **PC games and browser shooters** played with the mouse: the cursor *is* the gun, so no setup is needed.
- **Console mouse games** (Mario Paint): pick the core's Mouse device, not the light gun.

Keep the host's **Output** on **mouse** for all of the above. Use **gamepad** only when an
emulator wants aiming from a joystick, or for 2 players on Windows.

**Gotchas:** on Windows, set RetroArch's input driver to `raw`. Hide the cursor and play
fullscreen on the calibrated monitor. Some PS1/PS2 GunCon games have their own "shoot the
target" calibration: do it once, aiming carefully at the centre.

## Presets (host window → Emulator preset)

**Fix emulator settings** (button in the host's "Is the emulator seeing the gun?" box, or FIX IT ON
THE PC in the phone's Play screen check): MAME → `mame.ini` gun options. RetroArch → asks the core
which gun devices it has, picks the right one for the last game loaded (Konami games → Justifier,
Master System → Light Phaser, Genesis → Menacer, PS1 → GunCon), saves it as a game remap, and sets
that port's mouse index and gun buttons in `retroarch.cfg` (written when RetroArch closes, because it
rewrites that file on exit). Every edited file gets a one-time `.bak-lightgun` backup.

Where settings are found: the emulator in front, else a running emulator, else the last one used or
picked with **Choose emulator…**. RetroArch's `retroarch.cfg` is looked for next to the program, in
`%APPDATA%\RetroArch` (Windows), `~/.config/retroarch`, Flatpak and Snap folders (Linux) and
`~/Library/Application Support/RetroArch` (macOS); its remap/override/info/core folders are read from
that file. MAME: `mame.ini` next to the program or in `ini\` (Linux/macOS also `~/.mame`); if missing,
the fix runs `mame -createconfig` first. No admin rights are needed unless the emulator lives in
Program Files - then the host says to move it or run `start-host.bat` as administrator. Fixes asked
for from the phone are confirmed on the PC unless **Let the phone change emulator settings** is ticked.

**Auto-pick** (checkbox next to the preset, on by default): the host switches the preset to match
the emulator whose window is in front (MAME, RetroArch, DuckStation, PCSX2, Flycast, Dolphin,
Supermodel/Model 2, TeknoParrot). Other windows leave the preset alone. Only the buttons change;
your Output choice (mouse/gamepad) is kept. Untick it to choose by hand.

| Preset | Setup in the emulator |
|---|---|
| **RetroArch** | Settings → Drivers → Input = `raw` (Windows) or `udev` (Linux). Port 1 device type = the core's light gun (Beetle PSX "GunCon", Genesis Plus GX "MS Light Phaser" - but Menacer/Justifier go on **port 2**, Mesen/FCEUmm "Zapper", Snes9x "Super Scope", Flycast "Light Gun", MAME core). Bind Gun Trigger = mouse 1, Aux A = mouse 3, **Gun Reload / Offscreen shot = mouse 2**, Start = Enter, Select = Right Shift. Hide the cursor and run fullscreen. |
| **MAME** | `mame.ini` (next to mame.exe; create it if missing): `lightgun 1`, `lightgun_device lightgun`, **`lightgunprovider win32`** (the default `rawinput` ignores the phone's mouse on Windows). Reload = the host shoots at the screen corner (newer MAME has no `offscreen_reload` option; games reload on an off-screen shot by themselves). If you save MAME's configuration from its menu, check `lightgunprovider` is still `win32`. Start = 1, Coin = 5. |
| **DuckStation** | Controller port 1 = GunCon. Trigger = LMB, A = RMB, B = MMB, **Shoot Offscreen = R**. |
| **PCSX2** | USB port 1 = GunCon2, pointer from the mouse, **Shoot Offscreen = R**. Run the in-game calibration once. |
| **Flycast** | Port A = Light Gun. The mouse is the gun; RMB = reload. |
| **Dolphin** | Wii Remote → Point = Cursor X/Y (absolute; relative input off). A = LMB, B = RMB, 1/2 = keys 1/2, + = Enter, − = Esc. |
| **Model 2 / Supermodel** | Supermodel: `-input-system=rawinput`. Model 2: gun via mouse in the game's input config. |
| **TeknoParrot / DemulShooter** | Bind the gun to the mouse. Most titles reload on RMB. |
| **Virtual gamepad** | Map the right stick to the pointer/aim and RT to fire. Windows: install the ViGEmBus driver, then `pip install vgamepad` in the host venv. |

Button mappings live in `%APPDATA%\phone-lightgun\config.json` (Linux:
`~/.config/phone-lightgun/config.json`) under `buttons` and `offscreen_trigger`. Choosing a
preset overwrites them, and you can edit them by hand afterwards.

Always run the emulator **fullscreen on the monitor selected in the host**. The host
re-reads that monitor's geometry every 2 s, so emulators that switch resolution in
exclusive fullscreen keep their aim. If an emulator letterboxes (4:3 inside 16:9),
calibrate against the whole monitor anyway: the emulator maps the cursor into its own
viewport.

## Two players

**Joining:** on player 1's phone tap **+P2** (Play screen) or PCs > your PC > **+ ADD PLAYER 2**; or
click **Add player 2** in the host. The second phone taps **JOIN A FRIEND'S GAME** (first screen) or
PCs > + ADD PC, scans the code, and goes straight to Play - the PC already knows the TV, so it only
needs the quick lock (point at the TV). Press **ST** to join the running game.

**How player 2 reaches the game:** Windows has one cursor, so player 2 aims with a virtual Xbox
controller's right stick (click **Set up player 2 controller** once - installs the free ViGEmBus
driver). Linux gives each gun its own pointer, so both are real light guns there.

| Emulator | Player 2 |
|---|---|
| **MAME** | Works. Fix emulator settings maps P2's gun to the pad's right stick (`cfg/default.cfg`); P2 buttons are MAME's P2 keys: trigger = A, reload = shoot off-screen, Start = 2, Coin = 6. |
| Dolphin | Second Wii Remote: Point = controller right stick. |
| RetroArch (Windows) | Most cores aim guns only from a mouse, so P2 can't aim yet (needs a virtual multi-mouse driver). Linux: works. |

## Scope by platform

| Platform | Status |
|---|---|
| Windows | Mouse (SendInput absolute, per-monitor DPI aware), keys, and ViGEm gamepads. **Multi-gun is via gamepad output** (Windows merges all mice into one cursor). |
| Linux / Steam Deck desktop / Batocera | uinput absolute pointer + keys per gun (multi-gun native), and uinput gamepads. Needs write access to `/dev/uinput` (`udev` rule or the `input` group). |
| macOS | Mouse + a small key set via Quartz (single gun). |
| Consoles / original hardware | Out of scope. CRT-timed light-gun protocols can't be driven by an absolute pointer. |

Possible later additions, roughly ordered by value:
1. Gun4IR / Sinden-compatible HID device names, so front-ends auto-detect the gun.
2. Windows multi-mouse via a virtual HID driver, for emulators that only accept mice.
3. Per-game crosshair offset profiles.
