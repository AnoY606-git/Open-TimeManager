#!/usr/bin/env python3
"""Fail closed on repository policies that protect release hygiene and privacy."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath


ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
MAX_TRACKED_FILE_BYTES = 10 * 1024 * 1024
REQUIRED_LEGAL_FILES = {
    "LICENSE",
    "NOTICE",
    "AUTHORS.md",
    "ATTRIBUTION.md",
    "THIRD_PARTY_NOTICES.md",
    "app/src/main/assets/legal/LICENSE.txt",
    "app/src/main/assets/legal/NOTICE.txt",
    "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md",
}
PACKAGED_LEGAL_COPIES = {
    "app/src/main/assets/legal/LICENSE.txt": "LICENSE",
    "app/src/main/assets/legal/NOTICE.txt": "NOTICE",
    "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md": "THIRD_PARTY_NOTICES.md",
}
FORBIDDEN_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.QUERY_ALL_PACKAGES",
}
FORBIDDEN_BASENAMES = {
    "AGENTS.md",
    "local.properties",
    "keystore.properties",
    "BUILDING.md",
    "ARCHITECTURE.md",
    "TESTING.md",
    "RESEARCH.md",
}
FORBIDDEN_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx"}
FORBIDDEN_DIRS = {".gradle", ".kotlin", ".toolchain", ".superpowers", "docs/superpowers"}
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{30,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
)
DYNAMIC_VERSION = re.compile(
    r"(?im)^\s*[A-Za-z0-9_.-]+\s*=\s*[\"'](?:\+|[^\"']*\+|latest\.(?:release|integration)|[^\"']*-SNAPSHOT)[\"']\s*$"
)
ACTION_USE = re.compile(r"(?m)^\s*-?\s*uses:\s*([^\s#]+)")
FULL_SHA = re.compile(r"^[0-9a-fA-F]{40}$")


def tracked_files(root: Path) -> list[str]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        return []
    return [part.decode("utf-8", "surrogateescape") for part in result.stdout.split(b"\0") if part]


def read_text(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8")


def policy_path_errors(root: Path, tracked: set[str]) -> list[str]:
    errors: list[str] = []
    for relative in sorted(tracked):
        path = PurePosixPath(relative)
        if path.name in FORBIDDEN_BASENAMES:
            errors.append(f"tracked private/development file: {relative}")
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            errors.append(f"tracked signing material: {relative}")
        if any(relative == directory or relative.startswith(directory + "/") for directory in FORBIDDEN_DIRS):
            errors.append(f"tracked private/toolchain directory: {relative}")
        disk_path = root / relative
        if disk_path.is_file() and disk_path.stat().st_size > MAX_TRACKED_FILE_BYTES:
            errors.append(f"tracked file exceeds 10 MiB policy: {relative}")
    return errors


def secret_errors(root: Path, tracked: set[str]) -> list[str]:
    errors: list[str] = []
    for relative in sorted(tracked):
        path = root / relative
        if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if any(pattern.search(text) for pattern in SECRET_PATTERNS):
            errors.append(f"possible secret in tracked file: {relative}")
    return errors


def resource_names(path: Path) -> set[tuple[str, str]]:
    root = ET.parse(path).getroot()
    return {
        (child.tag, child.attrib["name"])
        for child in root
        if child.tag in {"string", "plurals", "string-array"}
        and "name" in child.attrib
        and child.attrib.get("translatable", "true") != "false"
    }


def translation_errors(root: Path, tracked: set[str]) -> list[str]:
    english = "app/src/main/res/values/strings.xml"
    russian = "app/src/main/res/values-ru/strings.xml"
    if english not in tracked or russian not in tracked:
        return ["English and Russian translation resources must both be tracked"]
    try:
        en_names = resource_names(root / english)
        ru_names = resource_names(root / russian)
    except (ET.ParseError, OSError, KeyError) as error:
        return [f"translation resource could not be parsed: {error}"]
    errors: list[str] = []
    missing_ru = sorted(en_names - ru_names)
    missing_en = sorted(ru_names - en_names)
    if missing_ru:
        errors.append("Russian translation is missing: " + ", ".join(f"{kind}/{name}" for kind, name in missing_ru))
    if missing_en:
        errors.append("English translation is missing: " + ", ".join(f"{kind}/{name}" for kind, name in missing_en))
    return errors


def workflow_errors(root: Path, tracked: set[str]) -> list[str]:
    errors: list[str] = []
    for relative in sorted(path for path in tracked if path.startswith(".github/workflows/") and path.endswith((".yml", ".yaml"))):
        for reference in ACTION_USE.findall(read_text(root, relative)):
            if reference.startswith(("./", "docker://")):
                continue
            if "@" not in reference or not FULL_SHA.fullmatch(reference.rsplit("@", 1)[1]):
                errors.append(f"GitHub action must use a full commit SHA: {relative}: {reference}")
    return errors


def android_permission_errors(root: Path, tracked: set[str]) -> list[str]:
    manifest = "app/src/main/AndroidManifest.xml"
    if manifest not in tracked:
        return ["Android manifest is not tracked"]
    try:
        document = ET.parse(root / manifest).getroot()
    except (ET.ParseError, OSError) as error:
        return [f"Android manifest could not be parsed: {error}"]
    declared = {
        node.attrib.get(ANDROID_NS + "name", "")
        for node in document.findall("uses-permission")
    }
    return [f"forbidden Android permission: {permission}" for permission in sorted(declared & FORBIDDEN_PERMISSIONS)]


def dependency_errors(root: Path, tracked: set[str]) -> list[str]:
    errors: list[str] = []
    candidates = [
        relative
        for relative in tracked
        if relative.endswith((".gradle", ".gradle.kts", "libs.versions.toml"))
    ]
    for relative in sorted(candidates):
        if DYNAMIC_VERSION.search(read_text(root, relative)):
            errors.append(f"dynamic dependency version: {relative}")
    return errors


def legal_errors(root: Path, tracked: set[str]) -> list[str]:
    errors = [f"missing required legal asset: {relative}" for relative in sorted(REQUIRED_LEGAL_FILES - tracked)]
    for packaged, source in PACKAGED_LEGAL_COPIES.items():
        if packaged in tracked and source in tracked:
            if (root / packaged).read_bytes() != (root / source).read_bytes():
                errors.append(f"packaged legal asset does not match {source}: {packaged}")
    return errors


def schema_errors(root: Path, tracked: set[str]) -> list[str]:
    schema_root = root / "app/schemas"
    if not schema_root.exists():
        return []
    errors: list[str] = []
    for path in schema_root.rglob("*.json"):
        relative = path.relative_to(root).as_posix()
        if relative not in tracked:
            errors.append(f"untracked Room schema: {relative}")
    return errors


def check(root: Path) -> list[str]:
    tracked = set(tracked_files(root))
    if not tracked:
        return ["repository has no tracked files or is not a Git worktree"]
    errors: list[str] = []
    errors.extend(policy_path_errors(root, tracked))
    errors.extend(secret_errors(root, tracked))
    errors.extend(translation_errors(root, tracked))
    errors.extend(workflow_errors(root, tracked))
    errors.extend(android_permission_errors(root, tracked))
    errors.extend(dependency_errors(root, tracked))
    errors.extend(legal_errors(root, tracked))
    errors.extend(schema_errors(root, tracked))
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    root = args.root.resolve()
    errors = check(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        print(f"Repository policy failed with {len(errors)} error(s).")
        return 1
    print("Repository policy: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
