# Open TimeManager

[Русский](README.md) · [Documentation](docs/README.md) · [Security](SECURITY.md) · [GPL-3.0-only](LICENSE)

Open TimeManager is an open, local-first work-hours tracker for Android 12 and later. It lets you start a timer in two taps, correct or add an entry manually, review totals, and keep control of your data without an account, advertising, or a cloud service.

The app does not request the `INTERNET` permission. Projects, notes, settings, and history remain in Android internal storage. Android automatic cloud backup is disabled; data leaves or enters the app only after an explicit action through the system document picker.

## Implemented features

- one durable timer with start, pause, resume, stop, and safe project switching;
- colored projects, active-entry notes, and project archiving without deleting history;
- manual entries, search, editing, confirmed deletion, a paged project picker, and bounded loading of older history;
- week, month, and custom reports up to 10 years, per-project totals, and UTF-8 CSV export;
- encrypted `.otmbackup` files with project metadata and preferences, retryable verified preview, Merge, and Replace modes;
- foreground-service notification, Quick Settings tile, and widget with stale-action protection;
- complete Russian and English UI, system/light/dark themes, and dynamic color;
- adaptive navigation for phones, landscape, split-screen, and wide windows;
- local privacy mode that hides project names from system surfaces.

Elapsed work is based on the device monotonic clock, while calendar boundaries use UTC and the recorded time zone. After a reboot or loss of a trustworthy monotonic reference, an unfinished entry requires explicit review instead of guessing the missing work time.

## Screenshots

The actual app running in an Android 16 / API 36 emulator, with synthetic demo data only.
[Capture setup and example records](docs/screenshots/README.md).

| Timer | History |
| --- | --- |
| <img src="docs/screenshots/en-timer.png" width="250" alt="Paused timer with a selected demo project"> | <img src="docs/screenshots/en-history.png" width="250" alt="History containing synthetic work entries"> |
| **Reports** | **Settings** |
| <img src="docs/screenshots/en-reports.png" width="250" alt="Report showing totals by demo project"> | <img src="docs/screenshots/en-settings.png" width="250" alt="Appearance, language, and privacy preferences"> |

## Install and build

The minimum version is Android 12 (API 31); the target is Android 16 / API 36. Repository builds do not use a production signing key. GitHub Actions provides a debug APK and an unsigned release APK; the repository owner must sign a release APK separately before distribution.

For a pinned local build on Linux x86_64, install Python 3.12+ and `curl`:

```bash
python3 scripts/bootstrap.py --accept-licenses
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The bootstrap verifies SHA-256 checksums for the pinned JDK 17, Android command-line tools, and Gradle Wrapper. You may instead provide JDK 17 and Android SDK API 36 with platform-tools, then set `JAVA_HOME` and either `ANDROID_HOME` or `ANDROID_SDK_ROOT`. The wrapper below recognizes those standard variables; explicit `OTM_JAVA_HOME`/`OTM_ANDROID_HOME` values take precedence.

The wrapper preserves standard `ANDROID_USER_HOME` and `GRADLE_USER_HOME` unless their `OTM_*` overrides are set. Bootstrap does not overwrite an existing `local.properties`: if it defines `sdk.dir`, keep that path consistent with the selected SDK so Gradle and wrapped `adb` use the same tools.

### Windows / PowerShell

The Linux bootstrap and `scripts/with-toolchain.sh` are not native Windows tools. Install JDK 17 and [Android command-line tools](https://developer.android.com/studio#command-tools), extracting the latter into `Android\Sdk\cmdline-tools\latest`. Keep the project in an ASCII-only path such as `C:\src\Open-TimeManager`: Android Gradle Plugin checks the project path on Windows.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
sdkmanager --sdk_root="$env:ANDROID_HOME" --licenses
sdkmanager --sdk_root="$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
.\gradlew.bat --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Replace `JAVA_HOME` with the actual JDK 17 directory. Gradle Wrapper downloads project dependencies; a separate Gradle installation is unnecessary. Python checks require Python 3.11+: `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` and `python scripts/check_repository.py`. Only Bash execution tests are skipped on native Windows; use Linux or WSL to execute those tests.

### Device check and installation

Installation requires an Android 12+ emulator or a connected device with USB debugging enabled; accept the RSA authorization prompt on the device. After building, confirm that the wrapped `adb devices` command reports it in the `device` state, then install the exact debug artifact:

```bash
scripts/with-toolchain.sh adb devices
scripts/with-toolchain.sh adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, after configuring the environment above, use:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with an automatically generated Android debug/test key and is for testing only. `app/build/outputs/apk/release/app-release-unsigned.apk` cannot be installed until its owner signs it with a release key.

CI includes SHA-256 checksums and the exact source commit with its APKs. Before distributing a signed APK, follow the [release checklist](docs/RELEASING.md): signing changes the checksum, and recipients must have access to the corresponding source code.

To run the repository's complete static and JVM build gate:

```bash
python3 scripts/check_repository.py
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:compileDebugAndroidTestKotlin
scripts/with-toolchain.sh python3 scripts/check_release_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

Release APK validation uses `aapt2` from Build Tools 36.0.0 via `ANDROID_HOME`/`ANDROID_SDK_ROOT` or explicit `--aapt2`. It checks the merged package/SDK/permissions, absence of debug/test-only flags, ZIP integrity, and exact offline legal assets; a signature is not required. The wrapper selects the build's SDK; with your own configured SDK you can invoke Python directly.

## Data and security

Backup files use the compatible `OTMB1` container: PBKDF2-HMAC-SHA256 with 310,000 iterations, AES-256-GCM, and an authenticated header. The current payload schema v2 preserves project colors and timestamps plus theme, dynamic color, privacy mode, and the daily goal; v1 remains readable with safe defaults. Language is selected in the app or inherited from the system; Android/AppCompat manages that choice, and it is not transferred in backups. The Android app enforces a **4 MiB** operational limit and at most 20,000 projects, entries, and segments. The underlying format and core codec have hard ceilings of 50 MiB and 200,000 elements. Those larger ceilings define the format; they are not the limits accepted by the Android UI.

A forgotten backup password cannot be recovered. A wrong password and damaged ciphertext deliberately produce the same outcome. A successful Replace permanently removes current local projects and history before importing within one database transaction—create and verify a separate backup first. The Room transaction rolls back on failure or cancellation, but cannot undo a successfully confirmed replacement. DataStore preferences converge after the Room commit through a durable journal; the app does not claim one atomic transaction across both stores. Restore is blocked while a timer is running, paused, or awaiting review.

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
