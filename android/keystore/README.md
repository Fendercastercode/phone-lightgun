# Release keystore - keep this out of git, back it up yourself

This folder holds the **release signing key** for the RetroSight Android app
(`phoneblaster-release.jks` - named from before the app was renamed; the file itself
doesn't need to change) and the passwords Gradle needs to use it
(`keystore.properties`). Both are gitignored and must stay that way - this
folder is `.gitignore`d entirely except for this file.

**If this keystore is lost, there is no recovery.** Google Play requires every
update to an app to be signed with the *same* key as the original upload. Lose
the key (and don't have Play App Signing enrolled - see below) and the only
option is publishing a brand-new app listing from zero downloads/reviews.

## Back this up now, somewhere outside this repo

Copy `phoneblaster-release.jks` and `keystore.properties` to a password
manager, an encrypted drive, or similar - anywhere that isn't "only on this
one dev machine." Do this before the first Play Console upload.

## Recommended: enroll in Play App Signing

On first upload to Play Console, opt into **Play App Signing**. Google then
holds the actual release-signing key in their own HSM, and you sign uploads
with this "upload key" instead, which CAN be reset/reissued through Play
Console's recovery form if it's ever lost. This removes the single point of
failure above. Keep this local keystore as the upload key either way.

## Regenerating (only if starting fresh, before any Play Store upload)

```bash
keytool -genkeypair -v -keystore keystore/retrosight-release.jks \
  -alias retrosight -keyalg RSA -keysize 4096 -validity 10957 \
  -dname "CN=RetroSight, OU=RetroSight, O=RetroSight, L=Unknown, ST=Unknown, C=US"
```

Then write matching `storeFile`, `storePassword`, `keyAlias`, `keyPassword`
lines into `keystore.properties` (see `app/build.gradle.kts` for the exact
property names it reads). Never do this once a real listing exists and has
real installs - it invalidates the ability to update that listing.

## CI

GitHub Actions does **not** have this keystore - `app/build.gradle.kts` falls
back to debug signing when `keystore.properties` is absent, so CI builds
build fine but aren't Play-upload-ready. To let CI produce a signable release
AAB, base64-encode this keystore and store it (plus the three passwords) as
repository secrets, then have the workflow reconstruct `keystore.properties`
at build time. Not set up yet - ask before wiring this up, since it means the
signing key leaves this machine for the first time.
