# Third-party notices

Open TimeManager uses the following third-party software. Versions are pinned in `gradle/libs.versions.toml`; transitive versions are resolved from published metadata and the Compose BOM. This notice does not relicense those works.

## Runtime dependencies

- **AndroidX Core, Activity, AppCompat, Lifecycle, DataStore, Room, and Jetpack Compose/Material 3** — Copyright The Android Open Source Project, Apache License 2.0. <https://www.apache.org/licenses/LICENSE-2.0>
- **Kotlin standard library, kotlinx.coroutines, and kotlinx.serialization** — Copyright JetBrains s.r.o. and contributors, Apache License 2.0. <https://www.apache.org/licenses/LICENSE-2.0>

## Build and test dependencies

- **Kotlin Gradle plugins** — JetBrains s.r.o. and contributors, Apache License 2.0.
- **Android Gradle Plugin and Android SDK tools** — The Android Open Source Project, Apache License 2.0 and applicable Android SDK terms.
- **Gradle and Gradle Wrapper** — Gradle, Inc. and contributors, Apache License 2.0. <https://gradle.org/license/>
- **KSP** — Google LLC and contributors, Apache License 2.0.
- **JUnit 4** — JUnit team, Eclipse Public License 1.0. <https://www.eclipse.org/legal/epl-v10.html>
- **Robolectric** — Robolectric contributors, MIT License. <https://opensource.org/license/mit>
- **AndroidX Test** — The Android Open Source Project, Apache License 2.0.

## Adapted build tooling

`scripts/bootstrap.py`, `scripts/with-toolchain.sh`, and `gradle/toolchain.json` were adapted from OpenAuth/SplitBox by AnoY606-git and contributors under GPL-3.0-only:

- <https://github.com/AnoY606-git/OpenAuth>
- <https://github.com/AnoY606-git/SplitBox>

The complete license for Open TimeManager and GPL-covered adapted files is in `LICENSE`. Full license texts for third-party binary dependencies are available from the linked upstream projects and published artifacts.
