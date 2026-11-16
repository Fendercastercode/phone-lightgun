# Cutting a release

## What happens automatically

Every push to `main` and every pull request runs `.github/workflows/ci.yml`:
host pytest on Windows + Linux, and an Android debug build + unit tests.

Pushing a tag like `v0.2.0` (or running the workflow manually from the Actions
tab) fires `.github/workflows/release.yml`, which:

1. Builds the PhoneBlaster host with PyInstaller for Windows, Linux x86_64,
   and 64-bit Raspberry Pi OS (aarch64) - three separate jobs, each on its own
   GitHub-hosted runner (the Pi build uses a native arm64 runner, not QEMU).
2. Packages each as a zip (Windows) or tarball (Linux/Pi) with its launcher
   script.
3. Writes a `SHA256SUMS*.txt` for each artifact.
4. Attests build provenance for each artifact via GitHub's Sigstore-backed
   `attest-build-provenance` action - this proves "built by this exact
   workflow run, from this exact commit," verifiable with the `gh attestation
   verify` CLI. It is **not** Windows code-signing; see below.
5. Attaches everything to a GitHub Release for that tag.

## What's intentionally NOT covered yet

- **32-bit Raspberry Pi OS (armv7l).** scipy/opencv-python/PySide6 don't
  reliably ship prebuilt wheels for that architecture, so a CI build would
  mean a slow, flaky from-source compile. Point those users (older Pi models,
  or a 32-bit install on a newer one) at `start-host.sh` instead, which
  builds locally with whatever's available on their own system.
- **Windows Authenticode signing.** The attestation step above does not stop
  the "Windows protected your PC" SmartScreen warning - SmartScreen doesn't
  read attestations. Options, cheapest first:
  - Ship unsigned + a README note on clicking through the warning + the
    checksums/attestation above for anyone who wants to verify integrity.
  - Apply for free signing through [SignPath.io](https://signpath.io) (open
    source program - public repo, OSI license, CI-driven signing).
  - [Azure/Trusted Signing](https://azure.microsoft.com/products/trusted-signing)
    (~$10/month flat) if SignPath's eligibility doesn't fit.
  - Whichever is chosen, it signs inside the `build-windows` job (there's a
    commented placeholder step) - never add a raw `.pfx`/password as a repo
    secret; both of the above services sign via a short-lived token instead.
- **Android release build/signing in CI.** `android-build` in `ci.yml` only
  builds+tests the debug variant. The release keystore
  (`android/keystore/phoneblaster-release.jks`) only exists locally right now
  (gitignored - see `android/keystore/README.md`). Wiring a release AAB build
  into CI means putting that keystore into repo secrets first - ask before
  doing that, since it's the first time the signing key would leave this
  machine.

## One-time setup this needed

- `host/run_host.py` - a tiny absolute-import wrapper PyInstaller's entry
  point uses instead of `lightgun_host/__main__.py` directly (which uses a
  relative import that only works via `python -m lightgun_host`, not when
  PyInstaller runs it as a standalone script).
- `host/pyinstaller.spec` - onedir build (keeps PySide6/Qt as separate files
  rather than one merged binary, which matters for its LGPL terms), no
  UPX (UPX-packed EXEs get flagged by antivirus heuristics far more often).
