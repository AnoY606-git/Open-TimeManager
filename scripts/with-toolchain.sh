#!/usr/bin/env bash
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
# На основе scripts/gradle.sh из https://github.com/AnoY606-git/SplitBox.
set -euo pipefail
OTM_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
OTM_LOCAL_JAVA_HOME="$OTM_ROOT/.toolchain/jdk"
OTM_LOCAL_ANDROID_HOME="$OTM_ROOT/.toolchain/android-sdk"
OTM_SYSTEM_JAVA_HOME="${JAVA_HOME:-}"
OTM_SYSTEM_ANDROID_HOME="${ANDROID_HOME:-}"
OTM_SYSTEM_ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-}"

otm_valid_java_home() {
    [[ -n "$1" && -x "$1/bin/java" ]]
}

otm_valid_android_home() {
    [[ -n "$1" && -x "$1/platform-tools/adb" ]]
}

OTM_LOCAL_TOOLCHAIN_COMPLETE=false
if otm_valid_java_home "$OTM_LOCAL_JAVA_HOME" && otm_valid_android_home "$OTM_LOCAL_ANDROID_HOME"; then
    OTM_LOCAL_TOOLCHAIN_COMPLETE=true
fi

if [[ -n "${OTM_JAVA_HOME:-}" ]]; then
    OTM_SELECTED_JAVA_HOME="$OTM_JAVA_HOME"
    if ! otm_valid_java_home "$OTM_SELECTED_JAVA_HOME"; then
        printf 'OTM_JAVA_HOME does not contain an executable bin/java: %s\n' "$OTM_SELECTED_JAVA_HOME" >&2
        exit 1
    fi
elif [[ "$OTM_LOCAL_TOOLCHAIN_COMPLETE" == true ]]; then
    OTM_SELECTED_JAVA_HOME="$OTM_LOCAL_JAVA_HOME"
elif otm_valid_java_home "$OTM_SYSTEM_JAVA_HOME"; then
    OTM_SELECTED_JAVA_HOME="$OTM_SYSTEM_JAVA_HOME"
else
    printf '%s\n' 'JDK not found. Run python3 scripts/bootstrap.py, set OTM_JAVA_HOME, or export a valid JAVA_HOME.' >&2
    exit 1
fi

if [[ -n "${OTM_ANDROID_HOME:-}" ]]; then
    OTM_SELECTED_ANDROID_HOME="$OTM_ANDROID_HOME"
    if ! otm_valid_android_home "$OTM_SELECTED_ANDROID_HOME"; then
        printf 'OTM_ANDROID_HOME does not contain an executable platform-tools/adb: %s\n' "$OTM_SELECTED_ANDROID_HOME" >&2
        exit 1
    fi
elif [[ "$OTM_LOCAL_TOOLCHAIN_COMPLETE" == true ]]; then
    OTM_SELECTED_ANDROID_HOME="$OTM_LOCAL_ANDROID_HOME"
elif otm_valid_android_home "$OTM_SYSTEM_ANDROID_HOME"; then
    OTM_SELECTED_ANDROID_HOME="$OTM_SYSTEM_ANDROID_HOME"
elif otm_valid_android_home "$OTM_SYSTEM_ANDROID_SDK_ROOT"; then
    OTM_SELECTED_ANDROID_HOME="$OTM_SYSTEM_ANDROID_SDK_ROOT"
else
    printf '%s\n' 'Android SDK platform-tools not found. Run python3 scripts/bootstrap.py, set OTM_ANDROID_HOME, or export a valid ANDROID_HOME/ANDROID_SDK_ROOT.' >&2
    exit 1
fi

export JAVA_HOME="$OTM_SELECTED_JAVA_HOME"
export ANDROID_HOME="$OTM_SELECTED_ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${OTM_ANDROID_USER_HOME:-$OTM_ROOT/.toolchain/android-user}"
export GRADLE_USER_HOME="${OTM_GRADLE_USER_HOME:-$OTM_ROOT/.toolchain/gradle-home}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p -- "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
cd -- "$OTM_ROOT"
if [[ $# -eq 0 ]]; then
    set -- ./gradlew
fi
exec "$@"
