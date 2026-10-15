# Open TimeManager

[Русский](README.md) · [Documentation](docs/README.md) · [Security](SECURITY.md) · [GPL-3.0-only](LICENSE)

Open TimeManager is an open, local-first work-hours tracker for Android 12 and later. It lets you start a timer in two taps, correct or add an entry manually, review totals, and keep control of your data without an account, advertising, or a cloud service.

The app does not request the `INTERNET` permission. Projects, notes, settings, and history remain in Android internal storage. Android automatic cloud backup is disabled; data leaves or enters the app only after an explicit action through the system document picker.

## Implemented features

- one durable timer with start, pause, resume, stop, and safe project switching;
- colored projects, active-entry notes, and project archiving without deleting history;
- manual entries, search, editing, and confirmed deletion;
- week, month, and custom reports, per-project totals, and UTF-8 CSV export;
- encrypted `.otmbackup` files with verified preview, Merge, and Replace modes;
- foreground-service notification, Quick Settings tile, and widget with stale-action protection;
- complete Russian and English UI, system/light/dark themes, and dynamic color;
- adaptive navigation for phones, landscape, split-screen, and wide windows;
- local privacy mode that hides project names from system surfaces.

Elapsed work is based on the device monotonic clock, while calendar boundaries use UTC and the recorded time zone. After a reboot or loss of a trustworthy monotonic reference, an unfinished entry requires explicit review instead of guessing the missing work time.

## Install and build

The minimum version is Android 12 (API 31); the target is Android 16 / API 36. Repository builds do not use a production signing key. GitHub Actions provides a debug APK and an unsigned release APK; the repository owner must sign a release APK separately before distribution.

For a pinned local build on Linux x86_64, install Python 3.12+ and `curl`:

```bash
python3 scripts/bootstrap.py --accept-licenses
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The bootstrap verifies SHA-256 checksums for the pinned JDK 17, Android command-line tools, and Gradle Wrapper. You may instead provide JDK 17 and Android SDK API 36, set `ANDROID_HOME`/`JAVA_HOME`, and invoke `./gradlew` directly.

Installation requires an Android 12+ emulator or a connected device with USB debugging enabled; accept the RSA authorization prompt on the device. After building, confirm that the wrapped `adb devices` command reports it in the `device` state, then install the exact debug artifact:

```bash
scripts/with-toolchain.sh adb devices
scripts/with-toolchain.sh adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with an automatically generated Android debug/test key and is for testing only. `app/build/outputs/apk/release/app-release-unsigned.apk` cannot be installed until its owner signs it with a release key.

To run the repository's complete static and JVM build gate:

```bash
python3 scripts/check_repository.py
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:compileDebugAndroidTestKotlin
```

## Data and security

Backup files use the `OTMB1` container: PBKDF2-HMAC-SHA256 with 310,000 iterations, AES-256-GCM, and an authenticated header. The Android app enforces a **4 MiB** operational limit and at most 20,000 projects, entries, and segments. The underlying format and core codec have hard ceilings of 50 MiB and 200,000 elements. Those larger ceilings define the format; they are not the limits accepted by the Android UI.

A forgotten backup password cannot be recovered. A wrong password and damaged ciphertext deliberately produce the same outcome. A successful Replace permanently removes current local projects and history before importing within one database transaction—create and verify a separate backup first. The transaction rolls back on failure, but cannot undo a successfully confirmed replacement. Restore is blocked while a timer is running, paused, or awaiting review.

See the [user guide](docs/USER_GUIDE.md), [security model](docs/SECURITY.md), and [backup/CSV format](docs/BACKUP_FORMAT.md).

## Preview limitations

- no synchronization, teams, web dashboard, location, payroll, or CSV import;
- transfer is manual through Android's Storage Access Framework;
- system controls depend on notification permission and OEM restrictions; in-app tracking remains available;
- the release APK is unsigned and not published on Google Play;
- no independent cryptographic audit, Google Play policy approval, or verified battery/performance benchmark is claimed;
- instrumentation tests are prepared for API 31 and 36 and run in CI; a local build alone is not evidence of physical-device execution.

## Contributing and license

Contributions are welcome under [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md), without placing sensitive details in a public issue.

Original Open TimeManager code is licensed under GNU GPL v3 only. Third-party components and adapted tooling are documented in [ATTRIBUTION.md](ATTRIBUTION.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The same legal texts are available offline from the app settings.
