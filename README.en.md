<p align="center">
  <img src="docs/assets/mark.svg" width="80" height="80" alt="Open TimeManager">
</p>

<h1 align="center">Open TimeManager</h1>

<p align="center">
  <strong>Your time. Your focus. Your data.</strong><br>
  A local-first Android work-hours tracker — without an account or cloud service.
</p>

<p align="center">
  <a href="#install"><img src="https://img.shields.io/badge/Android-12%2B-6251DA?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 12+"></a>
  <img src="https://img.shields.io/badge/Kotlin-Jetpack_Compose-242436?style=flat-square&amp;logo=kotlin&amp;logoColor=CDC3FF" alt="Kotlin and Jetpack Compose">
  <img src="https://img.shields.io/badge/Local--first-Offline-242436?style=flat-square" alt="Offline first">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--only-242436?style=flat-square" alt="License: GPL-3.0-only"></a>
</p>

<p align="center">
  <a href="https://github.com/AnoY606-git/Open-TimeManager/releases/latest">Download</a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="#features">Features</a> ·
  <a href="#install">Install</a> ·
  <a href="docs/README.md">Docs</a> ·
  <a href="README.md">Русский</a>
</p>

---

Start a timer in two taps, add or correct a work entry, and see where your hours go.
Open TimeManager keeps projects, notes, and history on your device—without an account, advertising, or a cloud service.

## Screenshots

Sixteen fresh **stable 1.1.0** (`versionCode = 3`) captures
from an **Android 16 / API 36 emulator**: eight each in English and Russian,
using synthetic demo data only. [Capture setup and example records](docs/screenshots/README.md).

| **01 · Timer** | **02 · History** |
| :---: | :---: |
| <img src="docs/screenshots/en-timer.png" width="280" alt="Paused timer with a selected demo project"> | <img src="docs/screenshots/en-history.png" width="280" alt="History containing synthetic work entries"> |
| **03 · Reports** | **04 · Settings** |
| <img src="docs/screenshots/en-reports.png" width="280" alt="Report showing totals by demo project"> | <img src="docs/screenshots/en-settings.png" width="280" alt="Settings 1.1 overview with five sections: appearance, tracking and projects, backups, help, and about"> |

<details>
<summary><strong>New 1.1 workflows · help, default project, and backup safety</strong></summary>

Real screens from the new version, not mockups or painted-over controls.

| **Offline help** | **Default project** | **Backup warning** |
| :---: | :---: | :---: |
| <img src="docs/screenshots/en-help.png" width="240" alt="Built-in offline guide with search across ten help topics"> | <img src="docs/screenshots/en-default-project.png" width="240" alt="Default-project picker with demo projects and the automatic fallback option"> | <img src="docs/screenshots/en-backup-warning.png" width="240" alt="Warning before creating .otmplain: the file is unencrypted and can be read or changed"> |

</details>

<details>
<summary>Dark theme</summary>

<p align="center">
  <img src="docs/screenshots/en-timer-dark.png" width="280" alt="Timer in the app's dark theme with a demo project">
</p>

</details>

## Features

| | Built for everyday tracking |
| --- | --- |
| **Focus** | One durable timer with start, pause, resume, stop, and safe project switching. Colored projects and notes for the active entry. |
| **History** | Manual entries, search, editing, and confirmed deletion. Archive projects without losing history; use a paged project picker and bounded loading of older entries. |
| **Reports** | Week, month, or custom periods up to 10 years. Per-project totals and UTF-8 CSV export. |
| **Your data** | Encrypted `.otmbackup` by default; a separate optional `.otmplain` without encryption or authenticity protection. Inspection preview and **Merge** or **Replace** restore modes. |
| **System controls** | Foreground-service notification, Quick Settings tile, and widget with stale-action protection. Privacy mode hides project names from system surfaces. |
| **Adaptive UI** | Complete English and Russian interfaces; system, light, and dark themes; dynamic color. Navigation adapts to phones, landscape, split-screen, and wide windows. |

Elapsed work uses the device's monotonic clock; calendar boundaries use UTC and the recorded time zone. After a reboot or loss of a trustworthy monotonic reference, an unfinished entry requires explicit review instead of guessing the missing work time.

In 1.1, Settings opens five focused screens: **Appearance**, **Tracking and
projects**, **Backups and restore**, **Help**, and **About**. The searchable,
10-topic English/Russian guide works offline. A local default-project preference
helps start work; an active session or explicit idle selection takes priority.
The preference is not transferred in backups.

Android 12+ supports device colors and a responsive widget with a preview.
Android 13+ can offer the system tile-add dialog; Android 12 uses manual tile
addition. Widget pinning requires launcher support and user confirmation.
System-settings links and manufacturer hints help troubleshoot restrictions,
without private OEM APIs or a blanket battery exemption. See the [user guide](docs/USER_GUIDE.md).

## Privacy by design

- **No network permission.** The app does not request `INTERNET`.
- **No accounts or ads.** No sign-in or cloud service is needed.
- **Local storage.** Projects, notes, settings, and history stay in Android internal storage; Android automatic cloud backup is disabled.
- **Manual transfers only.** Exports and imports happen only after your explicit action through Android's system document picker.

## Install

**Stable version: `1.1.0` (`versionCode = 3`).** Requires **Android 12+
(API 31)**; targets Android 16 / API 36. Read the [1.1.0 notes](docs/releases/1.1.0.md).
Download **`Open-TimeManager-1.1.0.apk`** from the
[v1.1.0 release page](https://github.com/AnoY606-git/Open-TimeManager/releases/tag/v1.1.0),
with final checksums, public certificate details, corresponding source and actual verification results.
This is not a Google Play release.

Before migrating from a preview/debug installation, stop the timer and create and
verify a separate encrypted backup. The production key differs from the debug
key; do not uninstall or clear app data to force an update. Version 1.1 retains
1.0's production key. Final assets document the certificate comparison and
data-preserving emulator upgrade; physical devices and all OEMs were not tested.
See the [upgrade precautions](docs/releases/1.1.0.md).

For development builds, open [GitHub Actions → CI](https://github.com/AnoY606-git/Open-TimeManager/actions/workflows/ci.yml), select a successful run, and look under **Artifacts** for `open-timemanager-debug-and-unsigned-release-apks`. Downloading Actions artifacts may require signing in. These debug/unsigned files are not the signed official release. See the [CI workflow](.github/workflows/ci.yml) for the build steps.

| Artifact | Purpose |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | Signed with an automatically generated Android debug/test key. **For testing only.** |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | **Unsigned; not installable as-is.** The repository owner must sign it separately with a release key before distribution. |

Repository builds do not use a production signing key. CI includes **SHA-256 checksums and the exact source commit** with its APKs. Before distributing a signed APK, follow the [release checklist](docs/RELEASING.md): verify the final signature, recompute the checksum after signing, and provide recipients with the corresponding source code. A private repository link alone does not provide source access to recipients who cannot open it.

## Build and verify

<details>
<summary><strong>Linux x86_64 · pinned toolchain</strong></summary>

For a pinned local build, install Python 3.12+ and `curl`:

```bash
python3 scripts/bootstrap.py --accept-licenses
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The bootstrap verifies SHA-256 checksums for the pinned JDK 17, Android command-line tools, and Gradle Wrapper. You may instead provide JDK 17 and Android SDK API 36 with platform-tools, then set `JAVA_HOME` and either `ANDROID_HOME` or `ANDROID_SDK_ROOT`. The wrapper recognizes those standard variables; explicit `OTM_JAVA_HOME`/`OTM_ANDROID_HOME` values take precedence.

The wrapper preserves standard `ANDROID_USER_HOME` and `GRADLE_USER_HOME` unless their `OTM_*` overrides are set. Bootstrap does not overwrite an existing `local.properties`: if it defines `sdk.dir`, keep that path consistent with the selected SDK so Gradle and wrapped `adb` use the same tools.

</details>

<details>
<summary><strong>Windows / PowerShell · JDK and SDK setup</strong></summary>

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

</details>

<details>
<summary><strong>Device check and APK installation</strong></summary>

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

If multiple devices or emulators are connected, select the intended serial from `adb devices` and use `adb -s SERIAL install -r` instead:

```bash
scripts/with-toolchain.sh adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Replace `SERIAL` with the selected device's actual serial. Updating an existing installation requires the same signing key. **Do not uninstall the app or clear its data to bypass a signing mismatch:** that destroys local records. Create and verify a separate backup before changing installations.

The debug APK is for testing only. `app/build/outputs/apk/release/app-release-unsigned.apk` cannot be installed until its owner signs it with a release key; see the [release checklist](docs/RELEASING.md).

</details>

<details>
<summary><strong>Complete static and JVM build gate</strong></summary>

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
python3 scripts/check_repository.py
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:compileDebugAndroidTestKotlin
scripts/with-toolchain.sh python3 scripts/check_release_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

Release APK validation uses `aapt2` from Build Tools 36.0.0 via `ANDROID_HOME`/`ANDROID_SDK_ROOT` or explicit `--aapt2`. It checks the merged package/SDK/permissions, absence of debug/test-only flags, ZIP integrity, and exact offline legal assets; a signature is not required. The wrapper selects the build's SDK; with your own configured SDK you can invoke Python directly.

With your own JDK/SDK, run `./gradlew` directly; on native Windows, use `.\gradlew.bat` and `python` for the equivalent checks. Compiling instrumentation tests does not run them on an emulator or device.

</details>

## Data and security

**A forgotten backup password cannot be recovered.** A wrong password and damaged ciphertext deliberately produce the same outcome.

**Before using Replace, create and verify a separate backup.** A successful replacement permanently removes current local projects and history before importing. Restore is blocked while a timer is running, paused, or awaiting review.

**`.otmplain` and CSV are readable plaintext.** Anyone holding an `.otmplain` file
can read or alter it; inspection cannot establish who created it. Android's
document picker may transfer files through a cloud provider if you select one.

<details>
<summary><strong>Backup compatibility, limits, and restore guarantees</strong></summary>

Encrypted `.otmbackup` files retain the compatible `OTMB1` container:
PBKDF2-HMAC-SHA256 with 310,000 iterations, AES-256-GCM, and an authenticated
header. This does not encrypt the local database, `.otmplain`, or CSV.

The current payload schema v2 preserves project colors and timestamps plus theme,
dynamic color, privacy mode, and the daily goal; v1 remains readable with safe
defaults. Language is managed by Android/AppCompat, and the default-project
preference stays local. Neither choice is transferred in backups.

Optional `.otmplain` uses a separate `OTMP1` container, the same schema v2 payload,
strict structural validation, and the same limits. It is **not encrypted or
authenticated**. Its SHA-256 checksum detects accidental damage, but anyone can
edit the data and recompute it. Creation and untrusted-file inspection have
separate warnings. The default create action stays encrypted; a password failure
does not authorize a plaintext fallback. Copies include confirmed, closed
intervals, not the current open interval: pause or stop before making a copy.

The Android app enforces a **4 MiB** operational limit and at most **20,000 projects, entries, and segments**. The underlying format and core codec have hard ceilings of 50 MiB and 200,000 elements. Those larger ceilings define the format; they are not the limits accepted by the Android UI.

Before export, the app completes any pending settings intent and takes a strictly sequenced snapshot of Room and DataStore; a read or reconciliation failure cancels file creation.

Replace imports within one Room database transaction. The transaction rolls back on failure or cancellation, but cannot undo a successfully confirmed replacement. DataStore preferences converge after the Room commit through a durable journal; later manual changes are ordered through the same journal and cannot be overwritten by an older restore intent. If DataStore saves a choice but the system privacy mirror cannot be written, the UI warns and the journal retries after restart. The app does not claim one atomic transaction across both stores.

</details>

See the [user guide](docs/USER_GUIDE.md), [security model](docs/SECURITY.md), and [backup/CSV format](docs/BACKUP_FORMAT.md).

## Limitations

- No synchronization, teams, web dashboard, location, payroll, or CSV import.
- Transfer is manual through Android's Storage Access Framework.
- System controls depend on notification permission and OEM restrictions; in-app tracking remains available.
- Signed releases are distributed through GitHub Releases, not Google Play.
- No independent cryptographic audit, Google Play policy approval, or verified battery/performance benchmark is claimed.
- CI is configured for API 31 and 36 instrumentation; the release's `VERIFICATION.md` records actual results. A local build alone is not evidence of physical-device execution.
- Redundant system updates are reduced, but battery savings are unmeasured. Android 16 Live Updates can be enabled separately for an active timer; Android and the OEM decide promotion. No private Super Island/HyperIsland integration is claimed.

## Contributing and license

Contributions are welcome under [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md), without placing sensitive details in a public issue.

Original Open TimeManager code is licensed under **[GNU GPL v3 only](LICENSE)** (`GPL-3.0-only`). Third-party components and adapted tooling are documented in [ATTRIBUTION.md](ATTRIBUTION.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The same legal texts are available offline from the app settings.

## Documentation

| Guide | What you'll find |
| --- | --- |
| [Documentation index](docs/README.md) | All project documentation in one place. |
| [User guide](docs/USER_GUIDE.md) | Timers, projects, history, reports, and transfers. |
| [Security and privacy](docs/SECURITY.md) | Storage, permissions, and the security model. |
| [Backup and CSV format](docs/BACKUP_FORMAT.md) | Compatibility, encryption, and data formats. |
| [Release checklist](docs/RELEASING.md) | Signing, checksums, verification, and corresponding source. |
| [Screenshot notes](docs/screenshots/README.md) | Capture setup and synthetic demo records. |
| [Changelog](CHANGELOG.md) | User-visible changes. |
| [Contributing](CONTRIBUTING.md) · [Report a vulnerability](SECURITY.md) | How to help and how to report security issues privately. |

---

<p align="center"><sub>Open source. Local data. Time for what matters.</sub></p>
