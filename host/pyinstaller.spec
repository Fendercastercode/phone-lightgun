# PyInstaller build spec for the PhoneBlaster PC host.
#
# Build with:  pyinstaller pyinstaller.spec
# Output:      dist/PhoneBlaster/  (onedir build - keeps Qt/PySide6 as separate DLLs/.so
#              files rather than merging everything into one binary, which matters for
#              PySide6's LGPL terms: users must be able to replace the Qt libraries, and
#              that's only straightforward with an onedir layout, not a single fat exe).
#
# This file intentionally does NOT embed any ROMs, BIOS files, or emulator cores - none
# of that ships with PhoneBlaster. See README.md ("Never bundle...") before changing that.
from PyInstaller.utils.hooks import collect_data_files

block_cipher = None

a = Analysis(
    ["run_host.py"],
    pathex=[],
    binaries=[],
    datas=collect_data_files("cv2") if False else [],  # opencv-python ships its own binaries; no extra datas needed
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib"],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="PhoneBlaster",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,          # UPX-packed EXEs get flagged by AV heuristics far more than unpacked ones
    console=False,      # GUI app - no console window
    icon=None,          # TODO: set an .ico once branding is finalized
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="PhoneBlaster",
)
