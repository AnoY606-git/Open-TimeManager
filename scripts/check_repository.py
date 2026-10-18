#!/usr/bin/env python3
"""Fail closed on repository policies that protect release hygiene and privacy."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tomllib
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
    "app/src/main/assets/legal/ATTRIBUTION.md",
    "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md",
}
PACKAGED_LEGAL_COPIES = {
    "app/src/main/assets/legal/LICENSE.txt": "LICENSE",
    "app/src/main/assets/legal/NOTICE.txt": "NOTICE",
    "app/src/main/assets/legal/ATTRIBUTION.md": "ATTRIBUTION.md",
    "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md": "THIRD_PARTY_NOTICES.md",
}
REQUIRED_RUNTIME_NOTICES = {
    "AndroidX",
    "Okio",
    "Guava ListenableFuture",
    "JSpecify",
    "datastore-preferences-external-protobuf",
    "BSD-3-Clause",
}
APACHE_LICENSE_MARKERS = (
    "Apache License",
    "Version 2.0, January 2004",
    "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
    "1. Definitions.",
    "2. Grant of Copyright License.",
    "3. Grant of Patent License.",
    "4. Redistribution.",
    "5. Submission of Contributions.",
    "6. Trademarks.",
    "7. Disclaimer of Warranty.",
    "8. Limitation of Liability.",
    "9. Accepting Warranty or Additional Liability.",
    "END OF TERMS AND CONDITIONS",
    "APPENDIX: How to apply the Apache License to your work.",
    "limitations under the License.",
)
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
    "agents.md",
    "local.properties",
    "keystore.properties",
    "signing.properties",
    "research.md",
}
FORBIDDEN_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
FORBIDDEN_BACKUP_SUFFIXES = {".otmbackup", ".otmplain"}
FORBIDDEN_ANDROID_ARTIFACT_SUFFIXES = {".apk", ".aab"}
FORBIDDEN_DIRS = {
    ".gradle", ".kotlin", ".toolchain", ".superpowers", ".agents", ".codex", ".claude",
    ".signing", "plans", "specs",
}
FORBIDDEN_DOC_DIRS = {"internal", "superpowers"}
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{30,}\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
)
DYNAMIC_ASSIGNMENT = re.compile(
    r"(?im)^\s*[A-Za-z0-9_.-]+\s*=\s*[\"'](?:\+|[^\"']*\+|latest\.(?:release|integration)|[^\"']*-SNAPSHOT)[\"']\s*$"
)
QUOTED_GRADLE_VALUE = re.compile(r'''(?P<quote>["'])(?P<value>[^"'\r\n]+)(?P=quote)''')
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
        parts = tuple(part.lower() for part in path.parts)
        name = parts[-1]
        if name in FORBIDDEN_BASENAMES:
            errors.append(f"tracked private/development file: {relative}")
        if name == ".env" or name.startswith(".env."):
            errors.append(f"tracked environment credentials: {relative}")
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            errors.append(f"tracked signing material: {relative}")
        if path.suffix.lower() in FORBIDDEN_BACKUP_SUFFIXES:
            errors.append(f"tracked private backup: {relative}")
        if path.suffix.lower() in FORBIDDEN_ANDROID_ARTIFACT_SUFFIXES:
            errors.append(f"tracked Android build artifact: {relative}")
        if any(part in FORBIDDEN_DIRS for part in parts) or any(
            parent == "docs" and child in FORBIDDEN_DOC_DIRS
            for parent, child in zip(parts, parts[1:])
        ):
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


TRANSLATED_RESOURCE_TAGS = {"string", "plurals", "string-array"}
FORMAT_ARGUMENT = re.compile(
    r"%(?:(?P<index>[1-9][0-9]*)\$)?(?P<flags>[-#+ 0,(<]*)"
    r"[0-9]*(?:\.[0-9]+)?(?:(?P<date>[tT])[A-Za-z]|(?P<conversion>[bBhHsScCdoxXeEfgGaA%n]))"
)


def translation_resources(
    root: Path, paths: list[str],
) -> tuple[dict[tuple[str, str], ET.Element], list[str]]:
    resources: dict[tuple[str, str], ET.Element] = {}
    locations: dict[tuple[str, str], str] = {}
    errors: list[str] = []
    for relative in paths:
        try:
            document = ET.parse(root / relative).getroot()
        except (ET.ParseError, OSError) as error:
            errors.append(f"translation resource could not be parsed: {relative}: {error}")
            continue
        for child in document:
            if child.tag not in TRANSLATED_RESOURCE_TAGS or "name" not in child.attrib:
                continue
            key = (child.tag, child.attrib["name"])
            if key in resources:
                errors.append(
                    f"duplicate translation resource: {key[0]}/{key[1]}: {locations[key]}, {relative}"
                )
            else:
                resources[key] = child
                locations[key] = relative
    return resources, errors


def format_arguments(element: ET.Element) -> set[tuple[int, str]]:
    """Compare argument identities/types, allowing translation reordering and reuse."""
    if element.attrib.get("formatted") == "false":
        return set()
    arguments: set[tuple[int, str]] = set()
    implicit_index = 1
    previous_index: int | None = None
    for match in FORMAT_ARGUMENT.finditer("".join(element.itertext())):
        conversion = match.group("conversion")
        if conversion in {"%", "n"} and not match.group("date"):
            continue
        if match.group("index"):
            index = int(match.group("index"))
        elif "<" in match.group("flags") and previous_index is not None:
            index = previous_index
        else:
            index = implicit_index
            implicit_index += 1
        previous_index = index
        arguments.add((index, "date" if match.group("date") else conversion.lower()))
    return arguments


def translation_format_errors(key: tuple[str, str], english: ET.Element, russian: ET.Element) -> list[str]:
    label = f"{key[0]}/{key[1]}"
    pairs: list[tuple[str, ET.Element, ET.Element]] = []
    errors: list[str] = []
    if key[0] == "string":
        pairs.append((label, english, russian))
    elif key[0] == "plurals":
        # Russian legitimately adds few/many forms. Compare those with the source's other form,
        # not with an identical set of locale-specific quantities.
        source = {item.attrib.get("quantity"): item for item in english.findall("item")}
        for item in russian.findall("item"):
            quantity = item.attrib.get("quantity")
            original = source.get(quantity, source.get("other"))
            if original is not None:
                pairs.append((f"{label}[{quantity}]", original, item))
    else:
        source_items = english.findall("item")
        translated_items = russian.findall("item")
        if len(source_items) != len(translated_items):
            errors.append(f"translation array length mismatch: {label}")
        pairs.extend(
            (f"{label}[{index}]", original, translated)
            for index, (original, translated) in enumerate(zip(source_items, translated_items))
        )
    for item_label, original, translated in pairs:
        if format_arguments(original) != format_arguments(translated):
            errors.append(f"translation format arguments mismatch: {item_label}")
    return errors


def translation_errors(root: Path, tracked: set[str]) -> list[str]:
    folders = ("app/src/main/res/values", "app/src/main/res/values-ru")
    paths = [
        sorted(
            relative for relative in tracked
            if str(PurePosixPath(relative).parent) == folder and relative.endswith(".xml")
        )
        for folder in folders
    ]
    if not all(paths):
        return ["English and Russian translation resources must both be tracked"]
    english, errors = translation_resources(root, paths[0])
    russian, ru_errors = translation_resources(root, paths[1])
    errors.extend(ru_errors)
    ignored = {key for key, item in english.items() if item.attrib.get("translatable") == "false"}
    en_names = set(english) - ignored
    ru_names = {
        key for key, item in russian.items()
        if key not in ignored and item.attrib.get("translatable") != "false"
    }
    missing_ru = sorted(en_names - ru_names)
    missing_en = sorted(ru_names - en_names)
    if missing_ru:
        errors.append("Russian translation is missing: " + ", ".join(f"{kind}/{name}" for kind, name in missing_ru))
    if missing_en:
        errors.append("English translation is missing: " + ", ".join(f"{kind}/{name}" for kind, name in missing_en))
    for key in sorted(en_names & ru_names):
        errors.extend(translation_format_errors(key, english[key], russian[key]))
    return errors


def _yaml_quoted_value(line: str, start: int) -> tuple[str | None, int]:
    quote = line[start]
    chars: list[str] = []
    index = start + 1
    while index < len(line):
        char = line[index]
        if char == quote:
            if quote == "'" and index + 1 < len(line) and line[index + 1] == "'":
                chars.append("'")
                index += 2
                continue
            return "".join(chars), index + 1
        if quote == '"' and char == "\\" and index + 1 < len(line):
            chars.append(line[index + 1])
            index += 2
            continue
        chars.append(char)
        index += 1
    return None, len(line)


def _strip_yaml_comment(line: str) -> str:
    index = 0
    while index < len(line):
        if line[index] in {'"', "'"}:
            _, index = _yaml_quoted_value(line, index)
            continue
        if line[index] == "#" and (index == 0 or line[index - 1].isspace()):
            return line[:index]
        index += 1
    return line


def workflow_action_references(text: str) -> tuple[list[str], int]:
    """Read action references from the safe YAML subset used by workflows.

    The standard library has no YAML parser. This scanner recognizes block and
    flow mapping keys, quoted keys/scalars, and fails closed when a ``uses``
    value cannot be read as one scalar. It intentionally does not interpret
    YAML aliases or multi-line scalars for action references.
    """
    references: list[str] = []
    invalid = 0
    for original_line in text.splitlines():
        line = _strip_yaml_comment(original_line)
        index = 0
        at_mapping_key = True
        while index < len(line):
            char = line[index]
            if char.isspace():
                index += 1
                continue
            if at_mapping_key and char == "-":
                index += 1
                continue
            if char in "{,":
                at_mapping_key = True
                index += 1
                continue
            if char in {'"', "'"}:
                value, after = _yaml_quoted_value(line, index)
                if at_mapping_key and value == "uses":
                    index = after
                else:
                    index = after
                    at_mapping_key = False
                    continue
            elif at_mapping_key and line.startswith("uses", index):
                after = index + len("uses")
                if after < len(line) and (line[after].isalnum() or line[after] in "_-"):
                    at_mapping_key = False
                    index += 1
                    continue
                index = after
            else:
                at_mapping_key = False
                index += 1
                continue

            while index < len(line) and line[index].isspace():
                index += 1
            if index >= len(line) or line[index] != ":":
                at_mapping_key = False
                continue
            index += 1
            while index < len(line) and line[index].isspace():
                index += 1
            if index >= len(line) or line[index] in "|>":
                invalid += 1
                break
            if line[index] in {'"', "'"}:
                reference, index = _yaml_quoted_value(line, index)
                if reference is None:
                    invalid += 1
                    break
            else:
                start = index
                while index < len(line) and not line[index].isspace() and line[index] not in ",}":
                    index += 1
                reference = line[start:index]
            if not reference:
                invalid += 1
            else:
                references.append(reference)
            at_mapping_key = False
    return references, invalid


def setup_android_package_errors(text: str, relative: str) -> list[str]:
    """Require an explicit package list so removed SDK defaults cannot break CI."""
    errors: list[str] = []
    lines = text.splitlines()
    for index, original_line in enumerate(lines):
        line = _strip_yaml_comment(original_line)
        if "android-actions/setup-android@" not in line:
            continue
        uses_indent = len(line) - len(line.lstrip())
        packages_value: str | None = None
        for candidate in lines[index + 1 :]:
            candidate_line = _strip_yaml_comment(candidate)
            stripped = candidate_line.strip()
            indent = len(candidate_line) - len(candidate_line.lstrip())
            if stripped.startswith("-") and indent <= uses_indent:
                break
            match = re.fullmatch(r'''["']?packages["']?\s*:\s*(.*?)\s*''', stripped)
            if match:
                packages_value = match.group(1)
                if len(packages_value) >= 2 and packages_value[0] == packages_value[-1] and packages_value[0] in {"'", '"'}:
                    packages_value = packages_value[1:-1]
                break
        if packages_value is None or "tools" in packages_value.split():
            errors.append(
                f"GitHub workflow must override setup-android packages without the removed tools package: {relative}"
            )
    return errors


def emulator_kvm_errors(text: str, relative: str) -> list[str]:
    """Require stable, resource-bounded Linux emulator jobs."""
    errors: list[str] = []
    lines = text.splitlines()
    for index, original_line in enumerate(lines):
        line = _strip_yaml_comment(original_line)
        if "reactivecircus/android-emulator-runner@" not in line:
            continue
        # Keep the permission setup in the same local step sequence. A marker
        # in another job must not make an emulator job appear accelerated.
        preceding_steps = "\n".join(lines[max(0, index - 24) : index])
        if (
            "sudo rm -rf -- /usr/local/lib/android/sdk/ndk" not in preceding_steps
            or "df -h /" not in preceding_steps
        ):
            errors.append(
                f"GitHub emulator job must free the unused Android NDK before startup: {relative}"
            )
        if "static_node=kvm" not in preceding_steps or "udevadm trigger --name-match=kvm" not in preceding_steps:
            errors.append(
                f"GitHub workflow must enable KVM before android-emulator-runner: {relative}"
            )
        uses_indent = len(line) - len(line.lstrip())
        disk_size: str | None = None
        for candidate in lines[index + 1 :]:
            candidate_line = _strip_yaml_comment(candidate)
            stripped = candidate_line.strip()
            indent = len(candidate_line) - len(candidate_line.lstrip())
            if stripped.startswith("-") and indent <= uses_indent:
                break
            match = re.fullmatch(r'''["']?disk-size["']?\s*:\s*(.*?)\s*''', stripped)
            if match:
                disk_size = match.group(1)
                if len(disk_size) >= 2 and disk_size[0] == disk_size[-1] and disk_size[0] in {"'", '"'}:
                    disk_size = disk_size[1:-1]
                break
        size_match = re.fullmatch(r"([1-9][0-9]*)([KMG]?)", disk_size or "", re.IGNORECASE)
        size_in_bytes = None
        if size_match:
            multiplier = {"": 1, "K": 1024, "M": 1024**2, "G": 1024**3}[size_match.group(2).upper()]
            size_in_bytes = int(size_match.group(1)) * multiplier
        if size_in_bytes is None or not 512 * 1024**2 <= size_in_bytes <= 4 * 1024**3:
            errors.append(
                f"GitHub workflow must set a bounded emulator disk size from 512M to 4G: {relative}"
            )
    return errors


def workflow_errors(root: Path, tracked: set[str]) -> list[str]:
    errors: list[str] = []
    for relative in sorted(path for path in tracked if path.startswith(".github/workflows/") and path.endswith((".yml", ".yaml"))):
        text = read_text(root, relative)
        references, invalid = workflow_action_references(text)
        if invalid:
            errors.append(f"GitHub action reference could not be parsed safely: {relative}")
        for reference in references:
            if reference.startswith(("./", "docker://")):
                continue
            if "@" not in reference or not FULL_SHA.fullmatch(reference.rsplit("@", 1)[1]):
                errors.append(f"GitHub action must use a full commit SHA: {relative}: {reference}")
        errors.extend(setup_android_package_errors(text, relative))
        errors.extend(emulator_kvm_errors(text, relative))
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


def is_dynamic_version(value: str) -> bool:
    normalized = value.strip().lower()
    return (
        "+" in normalized
        or normalized in {"latest.release", "latest.integration"}
        or normalized.endswith("-snapshot")
    )


def nested_strings(value: object) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [item for child in value.values() for item in nested_strings(child)]
    if isinstance(value, list):
        return [item for child in value for item in nested_strings(child)]
    return []


def catalog_version_values(document: dict[str, object]) -> list[str]:
    values = nested_strings(document.get("versions", {}))

    def visit(value: object) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key.lower() in {"version", "strictly", "prefer", "require", "requiredversion"}:
                    values.extend(nested_strings(child))
                else:
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    for section in ("libraries", "plugins"):
        visit(document.get(section, {}))
    return values


def strip_gradle_comments(text: str) -> str:
    """Remove Gradle comments while preserving strings and line boundaries."""
    result: list[str] = []
    index = 0
    quote: str | None = None
    while index < len(text):
        if quote is not None:
            if text.startswith(quote, index):
                result.append(quote)
                index += len(quote)
                quote = None
            elif len(quote) == 1 and text[index] == "\\" and index + 1 < len(text):
                result.append(text[index:index + 2])
                index += 2
            else:
                result.append(text[index])
                index += 1
            continue
        if text.startswith("//", index):
            while index < len(text) and text[index] != "\n":
                result.append(" ")
                index += 1
            continue
        if text.startswith("/*", index):
            result.extend("  ")
            index += 2
            while index < len(text) and not text.startswith("*/", index):
                result.append("\n" if text[index] == "\n" else " ")
                index += 1
            if index < len(text):
                result.extend("  ")
                index += 2
            continue
        if text.startswith(('"""', "'''"), index):
            quote = text[index:index + 3]
            result.append(quote)
            index += 3
            continue
        if text[index] in {'"', "'"}:
            quote = text[index]
        result.append(text[index])
        index += 1
    return "".join(result)


def dependency_errors(root: Path, tracked: set[str]) -> list[str]:
    errors: list[str] = []
    candidates = [
        relative
        for relative in tracked
        if relative.endswith((".gradle", ".gradle.kts", "libs.versions.toml"))
    ]
    for relative in sorted(candidates):
        text = read_text(root, relative)
        dynamic = False
        if relative.endswith("libs.versions.toml"):
            try:
                document = tomllib.loads(text)
            except tomllib.TOMLDecodeError as error:
                errors.append(f"dependency catalog could not be parsed: {relative}: {error}")
                continue
            dynamic = any(is_dynamic_version(value) for value in catalog_version_values(document))
        else:
            text = strip_gradle_comments(text)
            dynamic = DYNAMIC_ASSIGNMENT.search(text) is not None
            for match in QUOTED_GRADLE_VALUE.finditer(text):
                value = match.group("value")
                parts = value.split(":")
                if is_dynamic_version(value) or (len(parts) >= 3 and is_dynamic_version(parts[2])):
                    dynamic = True
                    break
        if dynamic:
            errors.append(f"dynamic dependency version: {relative}")
    return errors


def legal_errors(root: Path, tracked: set[str]) -> list[str]:
    errors = [f"missing required legal asset: {relative}" for relative in sorted(REQUIRED_LEGAL_FILES - tracked)]
    for packaged, source in PACKAGED_LEGAL_COPIES.items():
        if packaged in tracked and source in tracked:
            if (root / packaged).read_bytes() != (root / source).read_bytes():
                errors.append(f"packaged legal asset does not match {source}: {packaged}")
    notice = "THIRD_PARTY_NOTICES.md"
    if notice in tracked:
        contents = read_text(root, notice)
        for component in sorted(REQUIRED_RUNTIME_NOTICES):
            if component not in contents:
                errors.append(f"missing runtime legal notice for {component}: {notice}")
        normalized = " ".join(contents.split())
        if any(marker not in normalized for marker in APACHE_LICENSE_MARKERS):
            errors.append(f"missing complete Apache-2.0 license text: {notice}")
    return errors


def schema_errors(root: Path, tracked: set[str]) -> list[str]:
    schema_root = root / "app/schemas"
    errors: list[str] = []
    tracked_schemas = {
        relative
        for relative in tracked
        if relative.startswith("app/schemas/") and relative.endswith(".json")
    }
    if not tracked_schemas:
        errors.append("at least one tracked Room schema is required under app/schemas")
    if not schema_root.exists():
        return errors
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
