#!/usr/bin/env python3
"""Validate a built release APK, including its merged binary manifest and legal assets."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from check_repository import FORBIDDEN_PERMISSIONS, PACKAGED_LEGAL_COPIES


ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS_VERSION = "36.0.0"
EXPECTED_PACKAGE = "dev.opentimemanager.app"
EXPECTED_MIN_SDK = 31
EXPECTED_TARGET_SDK = 36
TOOL_TIMEOUT_SECONDS = 60
ATTRIBUTE = re.compile(r"([A-Za-z][A-Za-z0-9_-]*)='([^'\r\n]*)'")
MANIFEST_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")
PERMISSION_KINDS = {
    "uses-permission",
    "uses-permission-sdk-23",
    "uses-implied-permission",
    "optional-permission",
}


class ReleaseApkError(ValueError):
    """An artifact or tool result cannot satisfy the release policy safely."""


@dataclass(frozen=True)
class ManifestMetadata:
    package_name: str
    min_sdk: int
    target_sdk: int


def quoted_attributes(text: str, context: str) -> dict[str, str]:
    values: dict[str, str] = {}
    remaining = text.strip()
    while remaining:
        match = ATTRIBUTE.match(remaining)
        if match is None or (match.end() < len(remaining) and not remaining[match.end()].isspace()):
            raise ReleaseApkError(f"Unparseable {context} attributes")
        name, value = match.groups()
        if name in values:
            raise ReleaseApkError(f"Duplicate {context} attribute: {name}")
        values[name] = value
        remaining = remaining[match.end():].lstrip()
    return values


def parse_badging(text: str) -> ManifestMetadata:
    package_name: str | None = None
    min_sdk: int | None = None
    target_sdk: int | None = None
    for original in text.splitlines():
        line = original.strip()
        if line.startswith("package:"):
            if package_name is not None:
                raise ReleaseApkError("Duplicate package in aapt2 badging")
            attributes = quoted_attributes(line[len("package:"):], "package")
            if not {"name", "versionCode", "versionName"} <= attributes.keys():
                raise ReleaseApkError("Incomplete package in aapt2 badging")
            package_name = attributes["name"]
            if not MANIFEST_NAME.fullmatch(package_name) or not re.fullmatch(r"[0-9]+", attributes["versionCode"]):
                raise ReleaseApkError("Unparseable package in aapt2 badging")
        elif line.startswith(("sdkVersion", "minSdkVersion")):
            # SDK tools have used both spellings for the minimum SDK field.
            match = re.fullmatch(r"(?:sdkVersion|minSdkVersion):'([0-9]+)'", line)
            if match is None or min_sdk is not None:
                raise ReleaseApkError("Missing, duplicate, or unparseable minimum SDK in aapt2 badging")
            min_sdk = int(match[1])
        elif line.startswith("targetSdkVersion"):
            match = re.fullmatch(r"targetSdkVersion:'([0-9]+)'", line)
            if match is None or target_sdk is not None:
                raise ReleaseApkError("Missing, duplicate, or unparseable target SDK in aapt2 badging")
            target_sdk = int(match[1])
        elif line.startswith(("application-debuggable", "application-testOnly", "testOnly=")):
            raise ReleaseApkError("Debuggable or test-only APK is not a release artifact")
    if package_name is None or min_sdk is None or target_sdk is None:
        raise ReleaseApkError("Incomplete aapt2 badging: package, minimum SDK, and target SDK are required")
    return ManifestMetadata(package_name, min_sdk, target_sdk)


def parse_permissions(text: str) -> tuple[str, set[str]]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or not lines[0].startswith("package: "):
        raise ReleaseApkError("Missing package header in aapt2 permissions")
    package_name = lines[0][len("package: "):]
    if not MANIFEST_NAME.fullmatch(package_name):
        raise ReleaseApkError("Unparseable package header in aapt2 permissions")
    permissions: set[str] = set()
    has_permission_parent = False
    for line in lines[1:]:
        if line.startswith(("required-feature=", "required-not-feature=")):
            if not has_permission_parent or not re.fullmatch(r"required-(?:not-)?feature='[^'\r\n]+'", line):
                raise ReleaseApkError("Unparseable permission feature in aapt2 permissions")
            continue
        kind, separator, value = line.partition(": ")
        if kind == "permission" and separator:
            # A <permission> declaration is not a request for that permission.
            if not MANIFEST_NAME.fullmatch(value):
                raise ReleaseApkError("Unparseable permission declaration in aapt2 permissions")
            has_permission_parent = False
        elif kind in PERMISSION_KINDS and separator:
            attributes = quoted_attributes(value, "permission")
            name = attributes.get("name", "")
            if not MANIFEST_NAME.fullmatch(name):
                raise ReleaseApkError("Missing or unparseable permission name in aapt2 permissions")
            if "maxSdkVersion" in attributes and not re.fullmatch(r"[0-9]+", attributes["maxSdkVersion"]):
                raise ReleaseApkError("Unparseable permission maxSdkVersion in aapt2 permissions")
            permissions.add(name)
            has_permission_parent = kind == "uses-permission"
        else:
            raise ReleaseApkError("Unrecognized output in aapt2 permissions")
    return package_name, permissions


def find_aapt2(explicit: Path | None = None) -> Path:
    if explicit is not None:
        if not explicit.is_file():
            raise ReleaseApkError(f"Explicit aapt2 file does not exist: {explicit}")
        return explicit.resolve()
    executable = "aapt2.exe" if os.name == "nt" else "aapt2"
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        home = os.environ.get(name)
        if home:
            candidate = Path(home) / "build-tools" / BUILD_TOOLS_VERSION / executable
            if candidate.is_file():
                return candidate.resolve()
    raise ReleaseApkError(
        f"aapt2 not found: pass --aapt2 or set ANDROID_HOME/ANDROID_SDK_ROOT to an SDK with Build Tools {BUILD_TOOLS_VERSION}"
    )


def dump_manifest(aapt2: Path, command: str, apk: Path) -> str:
    try:
        result = subprocess.run(
            [str(aapt2), "dump", command, str(apk)],
            capture_output=True,
            encoding="utf-8",
            errors="strict",
            check=False,
            timeout=TOOL_TIMEOUT_SECONDS,
        )
    except (OSError, UnicodeError, subprocess.TimeoutExpired) as error:
        raise ReleaseApkError(f"aapt2 dump {command} failed: {error}") from error
    if result.returncode != 0:
        detail = result.stderr.strip()[:2000]
        raise ReleaseApkError(f"aapt2 dump {command} exited with {result.returncode}: {detail}")
    if not result.stdout.strip():
        raise ReleaseApkError(f"aapt2 dump {command} returned empty output")
    return result.stdout


def check_zip_and_legal(apk: Path, root: Path) -> None:
    try:
        with zipfile.ZipFile(apk) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(names) != len(set(names)):
                raise ReleaseApkError("APK ZIP contains duplicate entries")
            if any(entry.flag_bits & 1 for entry in entries):
                raise ReleaseApkError("APK ZIP contains encrypted entries")
            if "AndroidManifest.xml" not in names or archive.getinfo("AndroidManifest.xml").file_size == 0:
                raise ReleaseApkError("APK ZIP is missing a nonempty AndroidManifest.xml")
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise ReleaseApkError(f"APK ZIP CRC/header check failed: {bad_entry}")
            for packaged, source in sorted(PACKAGED_LEGAL_COPIES.items()):
                member = PurePosixPath(packaged).relative_to("app/src/main").as_posix()
                if member not in names:
                    raise ReleaseApkError(f"APK is missing legal asset: {member}")
                expected = (root / source).read_bytes()
                if not expected:
                    raise ReleaseApkError(f"Root legal document is empty: {source}")
                # Compare build inputs exactly; do not hide stale content or newline changes.
                if archive.read(member) != expected:
                    raise ReleaseApkError(f"APK legal asset does not match root {source}: {member}")
    except (OSError, EOFError, RuntimeError, zipfile.BadZipFile, zipfile.LargeZipFile, zlib.error) as error:
        raise ReleaseApkError(f"Cannot validate APK ZIP/legal assets: {error}") from error


def check_release_apk(apk: Path, root: Path = ROOT, aapt2: Path | None = None) -> ManifestMetadata:
    apk = apk.resolve()
    if not apk.is_file():
        raise ReleaseApkError(f"APK file does not exist: {apk}")
    check_zip_and_legal(apk, root)
    tool = find_aapt2(aapt2)
    metadata = parse_badging(dump_manifest(tool, "badging", apk))
    if metadata != ManifestMetadata(EXPECTED_PACKAGE, EXPECTED_MIN_SDK, EXPECTED_TARGET_SDK):
        raise ReleaseApkError(
            f"Unexpected APK metadata: {metadata}; expected package {EXPECTED_PACKAGE}, minSdk {EXPECTED_MIN_SDK}, targetSdk {EXPECTED_TARGET_SDK}"
        )
    package_name, permissions = parse_permissions(dump_manifest(tool, "permissions", apk))
    if package_name != metadata.package_name:
        raise ReleaseApkError("Package in aapt2 permissions does not match release badging")
    forbidden = permissions & FORBIDDEN_PERMISSIONS
    if forbidden:
        raise ReleaseApkError("Forbidden release APK permissions: " + ", ".join(sorted(forbidden)))
    return metadata


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Built release APK; an unsigned APK is accepted")
    parser.add_argument("--aapt2", type=Path, help="Explicit SDK aapt2 executable (otherwise Build Tools 36.0.0 in SDK environment)")
    parser.add_argument("--root", type=Path, default=ROOT, help="Source checkout containing the root legal documents")
    args = parser.parse_args(argv)
    try:
        check_release_apk(args.apk, args.root, args.aapt2)
    except (ReleaseApkError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Release APK policy: OK (manifest, ZIP/CRC, and exact legal assets; no signature required)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
