# Third-party notices

Open TimeManager uses the following third-party software. This inventory was checked against the Gradle `releaseRuntimeClasspath` for version 0.1.0-preview and the license entries packaged in the unsigned release APK. Direct versions are pinned in `gradle/libs.versions.toml`; Gradle may select a newer compatible transitive version from published metadata or the Compose BOM. This notice does not relicense those works.

## Release runtime dependencies

- **AndroidX** — Core, Activity, AppCompat, Lifecycle, SavedState, DataStore, Room, SQLite, Profile Installer, Compose UI/Foundation/Runtime/Material/Material 3, and their AndroidX transitive support artifacts. Copyright The Android Open Source Project. Apache License 2.0. The relevant AAR license entries are retained under `META-INF/androidx/` in the APK. <https://source.android.com/docs/setup/about/licenses>
- **Kotlin standard library 2.3.20 and JetBrains annotations 23.0.0** — JetBrains s.r.o. and contributors. Apache License 2.0. <https://github.com/JetBrains/kotlin/blob/v2.3.20/license/LICENSE.txt>
- **kotlinx.coroutines 1.11.0 and kotlinx.serialization 1.11.0** — JetBrains s.r.o. and contributors. Apache License 2.0. <https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/LICENSE.txt> and <https://github.com/Kotlin/kotlinx.serialization/blob/v1.11.0/LICENSE.txt>
- **Okio 3.9.1** (`com.squareup.okio:okio-jvm`) — Square, Inc. and contributors. Apache License 2.0. <https://github.com/square/okio/blob/3.9.1/LICENSE.txt>
- **Guava ListenableFuture 1.0** (`com.google.guava:listenablefuture`) — Google and Guava contributors. Apache License 2.0, inherited from the published Guava parent metadata. <https://central.sonatype.com/artifact/com.google.guava/listenablefuture/1.0>
- **JSpecify 1.0.0** (`org.jspecify:jspecify`) — JSpecify authors and contributors. Apache License 2.0. <https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE>
- **DataStore Preferences External Protobuf 1.2.1** (`androidx.datastore:datastore-preferences-external-protobuf`) — repackaged proto-lite code published by The Android Open Source Project. BSD-3-Clause. The artifact's canonical license file is retained in the APK at `META-INF/androidx/datastore/datastore-preferences-external-protobuf/LICENSE.txt` and reproduced below.

The Apache License 2.0 text is available at <https://www.apache.org/licenses/LICENSE-2.0.txt>.

### DataStore Preferences External Protobuf BSD-3-Clause text

```text
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

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

The complete license for Open TimeManager and GPL-covered adapted files is in `LICENSE`. License metadata was checked against the published POMs for the resolved artifacts. The linked upstream files and license entries carried inside the APK are authoritative for the respective third-party works.
