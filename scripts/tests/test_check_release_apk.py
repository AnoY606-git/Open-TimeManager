import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path, PurePosixPath
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_release_apk as checker


BADGING = """package: name='dev.opentimemanager.app' versionCode='1' versionName='0.1.0-preview' compileSdkVersion='36'
sdkVersion:'31'
targetSdkVersion:'36'
application: label='Open TimeManager' icon='res/icon.xml'
launchable-activity: name='dev.opentimemanager.app.MainActivity' label='' icon=''
"""
PERMISSIONS = """package: dev.opentimemanager.app
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_SPECIAL_USE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
permission: dev.opentimemanager.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='dev.opentimemanager.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
"""


class ManifestParsingTest(unittest.TestCase):
    def test_badging_accepts_both_minimum_sdk_spellings_and_crlf(self) -> None:
        expected = checker.ManifestMetadata("dev.opentimemanager.app", 31, 36)
        for field in ("sdkVersion", "minSdkVersion"):
            with self.subTest(field=field):
                text = BADGING.replace("sdkVersion", field).replace("\n", "\r\n")
                self.assertEqual(checker.parse_badging(text), expected)

    def test_badging_rejects_missing_malformed_or_ambiguous_metadata(self) -> None:
        for text in (
            "",
            "unexpected output",
            BADGING.replace("sdkVersion:'31'\n", ""),
            BADGING.replace("targetSdkVersion:'36'\n", ""),
            BADGING.replace("sdkVersion:'31'", "sdkVersion:'S'"),
            BADGING.replace("targetSdkVersion:'36'", "targetSdkVersion:36"),
            BADGING.replace("name='dev.opentimemanager.app'", "name=dev.opentimemanager.app"),
            BADGING.replace("versionCode='1'", "versionCode='invalid'"),
            BADGING.replace(" versionName='0.1.0-preview'", ""),
            BADGING.replace("name='dev.opentimemanager.app'", "name=''"),
            BADGING.replace("versionCode='1'", "versionCode='1'versionName='bad'"),
            BADGING + "sdkVersion:'31'\n",
            BADGING + "minSdkVersion:'31'\n",
            BADGING + "targetSdkVersion:'36'\n",
            BADGING + BADGING.splitlines()[0] + "\n",
            BADGING.replace("versionCode='1'", "name='other.package' versionCode='1'"),
        ):
            with self.subTest(text=text), self.assertRaises(checker.ReleaseApkError):
                checker.parse_badging(text)

    def test_badging_rejects_debug_and_test_only_artifacts(self) -> None:
        for flag in ("application-debuggable", "application-testOnly", "testOnly='-1'"):
            with self.subTest(flag=flag), self.assertRaisesRegex(checker.ReleaseApkError, "not a release"):
                checker.parse_badging(BADGING + flag + "\n")

    def test_permissions_distinguish_requests_from_definitions(self) -> None:
        package, permissions = checker.parse_permissions(
            PERMISSIONS + "permission: android.permission.INTERNET\n"
        )
        self.assertEqual(package, checker.EXPECTED_PACKAGE)
        self.assertIn("android.permission.POST_NOTIFICATIONS", permissions)
        self.assertNotIn("android.permission.INTERNET", permissions)

    def test_permissions_accept_sdk23_implied_optional_and_feature_records(self) -> None:
        package, permissions = checker.parse_permissions(
            PERMISSIONS
            + "uses-permission: name='example.permission.NORMAL' maxSdkVersion='36' usesPermissionFlags='neverForLocation'\n"
            + "  required-feature='example.feature.first'\n"
            + "  required-not-feature='example.feature.second'\n"
            + "optional-permission: name='example.permission.NORMAL' maxSdkVersion='36'\n"
            + "uses-permission-sdk-23: name='example.permission.SDK23' maxSdkVersion='35'\n"
            + "uses-implied-permission: name='example.permission.IMPLIED' reason='compatibility'\n"
        )
        self.assertEqual(package, checker.EXPECTED_PACKAGE)
        self.assertTrue({"example.permission.NORMAL", "example.permission.SDK23", "example.permission.IMPLIED"} <= permissions)

    def test_permissions_allow_a_valid_manifest_with_no_permission_requests(self) -> None:
        self.assertEqual(checker.parse_permissions("package: dev.opentimemanager.app\n"), (checker.EXPECTED_PACKAGE, set()))

    def test_permissions_reject_unrecognized_or_incomplete_output(self) -> None:
        for text in (
            "",
            "unrecognized output",
            "uses-permission: name='android.permission.INTERNET'\n",
            "package: \n",
            "package: name='dev.opentimemanager.app'\n",
            PERMISSIONS + "package: dev.opentimemanager.app\n",
            PERMISSIONS + "uses-permission: \n",
            PERMISSIONS + "uses-permission: name=''\n",
            PERMISSIONS + "uses-permission: name=android.permission.INTERNET\n",
            PERMISSIONS + "uses-permission: name='example.permission.FIRST' name='android.permission.INTERNET'\n",
            PERMISSIONS + "uses-permission: name='example.permission.FIRST' trailing junk\n",
            PERMISSIONS + "uses-permission: name='example.permission.FIRST' maxSdkVersion='invalid'\n",
            PERMISSIONS + "uses-permission-sdk-24: name='android.permission.INTERNET'\n",
            PERMISSIONS + "permission: \n",
            "package: dev.opentimemanager.app\n  required-feature='example.feature'\n",
            PERMISSIONS + "warning: could not read remaining permissions\n",
        ):
            with self.subTest(text=text), self.assertRaises(checker.ReleaseApkError):
                checker.parse_permissions(text)


class ReleaseApkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "source checkout"
        self.root.mkdir()
        self.apk = self.root / "unsigned release.apk"
        self.tool = self.root / "SDK tools" / ("aapt2.exe" if os.name == "nt" else "aapt2")
        self.tool.parent.mkdir()
        self.tool.write_bytes(b"mock tool; never executed")
        self.members = {"AndroidManifest.xml": b"synthetic binary manifest", "classes.dex": b"synthetic dex payload"}
        for packaged, source in checker.PACKAGED_LEGAL_COPIES.items():
            contents = f"Complete {source}: legal text including non-ASCII пример\n".encode("utf-8")
            (self.root / source).write_bytes(contents)
            member = PurePosixPath(packaged).relative_to("app/src/main").as_posix()
            self.members[member] = contents
        self.write_apk()
        # Every test is isolated from installed SDKs and cannot invoke a real tool.
        self.run = self.enterContext(patch.object(checker.subprocess, "run", side_effect=self.tool_output))
        self.enterContext(patch.dict(os.environ, {}, clear=True))

    def write_apk(self, members: dict[str, bytes] | None = None) -> None:
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_STORED) as archive:
            for name, contents in (self.members if members is None else members).items():
                archive.writestr(name, contents)

    @staticmethod
    def tool_output(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
        if command[1:3] == ["dump", "badging"]:
            return subprocess.CompletedProcess(command, 0, BADGING, "")
        if command[1:3] == ["dump", "permissions"]:
            return subprocess.CompletedProcess(command, 0, PERMISSIONS, "")
        raise AssertionError(f"Unexpected subprocess invocation: {command}")

    def check(self) -> checker.ManifestMetadata:
        return checker.check_release_apk(self.apk, self.root, self.tool)

    def cli(self, *arguments: str) -> tuple[int, str, str]:
        output, errors = io.StringIO(), io.StringIO()
        with redirect_stdout(output), redirect_stderr(errors):
            result = checker.main([str(self.apk), "--root", str(self.root), *arguments])
        return result, output.getvalue(), errors.getvalue()

    def test_accepts_unsigned_zip_and_calls_explicit_tool_without_a_shell(self) -> None:
        self.assertEqual(self.check(), checker.ManifestMetadata(checker.EXPECTED_PACKAGE, 31, 36))
        self.assertEqual(self.run.call_count, 2)
        for call, command in zip(self.run.call_args_list, ("badging", "permissions")):
            self.assertEqual(call.args[0], [str(self.tool.resolve()), "dump", command, str(self.apk.resolve())])
            self.assertEqual(call.kwargs["encoding"], "utf-8")
            self.assertEqual(call.kwargs["errors"], "strict")
            self.assertEqual(call.kwargs["timeout"], checker.TOOL_TIMEOUT_SECONDS)
            self.assertFalse(call.kwargs.get("shell", False))

    def test_rejects_each_forbidden_permission_even_with_a_max_sdk(self) -> None:
        for permission in sorted(checker.FORBIDDEN_PERMISSIONS):
            with self.subTest(permission=permission):
                self.run.side_effect = [
                    subprocess.CompletedProcess([], 0, BADGING, ""),
                    subprocess.CompletedProcess([], 0, PERMISSIONS + f"uses-permission: name='{permission}' maxSdkVersion='32'\n", ""),
                ]
                with self.assertRaisesRegex(checker.ReleaseApkError, "Forbidden release APK permissions"):
                    self.check()

    def test_rejects_forbidden_sdk23_implied_and_optional_requests(self) -> None:
        for kind in ("uses-permission-sdk-23", "uses-implied-permission", "optional-permission"):
            with self.subTest(kind=kind):
                self.run.side_effect = [
                    subprocess.CompletedProcess([], 0, BADGING, ""),
                    subprocess.CompletedProcess([], 0, PERMISSIONS + f"{kind}: name='android.permission.INTERNET'\n", ""),
                ]
                with self.assertRaisesRegex(checker.ReleaseApkError, "Forbidden release APK permissions"):
                    self.check()

    def test_rejects_wrong_package_minimum_or_target_sdk(self) -> None:
        for badging in (
            BADGING.replace(checker.EXPECTED_PACKAGE, "other.application"),
            BADGING.replace("sdkVersion:'31'", "sdkVersion:'34'"),
            BADGING.replace("targetSdkVersion:'36'", "targetSdkVersion:'35'"),
        ):
            with self.subTest(badging=badging):
                self.run.side_effect = [subprocess.CompletedProcess([], 0, badging, "")]
                with self.assertRaisesRegex(checker.ReleaseApkError, "Unexpected APK metadata"):
                    self.check()

    def test_rejects_inconsistent_package_between_tool_commands(self) -> None:
        self.run.side_effect = [
            subprocess.CompletedProcess([], 0, BADGING, ""),
            subprocess.CompletedProcess([], 0, PERMISSIONS.replace(checker.EXPECTED_PACKAGE, "other.application"), ""),
        ]
        with self.assertRaisesRegex(checker.ReleaseApkError, "does not match release badging"):
            self.check()

    def test_rejects_missing_or_directory_apk_without_invoking_tool(self) -> None:
        self.apk.unlink()
        for make_directory in (False, True):
            if make_directory:
                self.apk.mkdir()
            with self.subTest(directory=make_directory), self.assertRaisesRegex(checker.ReleaseApkError, "APK file"):
                self.check()
        self.run.assert_not_called()

    def test_rejects_invalid_and_truncated_zip_without_invoking_tool(self) -> None:
        truncated = self.apk.read_bytes()[:-10]
        for contents in (b"not a ZIP", truncated):
            self.apk.write_bytes(contents)
            with self.subTest(contents=contents[:20]), self.assertRaisesRegex(checker.ReleaseApkError, "ZIP"):
                self.check()
        self.run.assert_not_called()

    def test_crc_checks_nonlegal_entries_too(self) -> None:
        contents = bytearray(self.apk.read_bytes())
        contents[contents.index(b"synthetic dex payload")] ^= 1
        self.apk.write_bytes(contents)
        with self.assertRaisesRegex(checker.ReleaseApkError, "CRC/header check failed: classes.dex"):
            self.check()
        self.run.assert_not_called()

    def test_rejects_duplicate_zip_entries(self) -> None:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as archive:
                archive.writestr("AndroidManifest.xml", b"second manifest")
        with self.assertRaisesRegex(checker.ReleaseApkError, "duplicate entries"):
            self.check()
        self.run.assert_not_called()

    def test_rejects_encrypted_zip_entries(self) -> None:
        contents = bytearray(self.apk.read_bytes())
        central = contents.index(b"PK\x01\x02")
        flags = struct.unpack_from("<H", contents, central + 8)[0]
        struct.pack_into("<H", contents, central + 8, flags | 1)
        self.apk.write_bytes(contents)
        with self.assertRaisesRegex(checker.ReleaseApkError, "encrypted entries"):
            self.check()
        self.run.assert_not_called()

    def test_rejects_missing_or_empty_manifest(self) -> None:
        for contents in (None, b""):
            members = dict(self.members)
            if contents is None:
                del members["AndroidManifest.xml"]
            else:
                members["AndroidManifest.xml"] = contents
            self.write_apk(members)
            with self.subTest(contents=contents), self.assertRaisesRegex(checker.ReleaseApkError, "AndroidManifest.xml"):
                self.check()

    def test_rejects_each_missing_legal_asset(self) -> None:
        for member in self.members:
            if member.startswith("assets/legal/"):
                self.write_apk({name: contents for name, contents in self.members.items() if name != member})
                with self.subTest(member=member), self.assertRaisesRegex(checker.ReleaseApkError, "missing legal asset"):
                    self.check()
        self.run.assert_not_called()

    def test_rejects_each_stale_legal_asset(self) -> None:
        for member, contents in self.members.items():
            if member.startswith("assets/legal/"):
                self.write_apk({**self.members, member: contents + b"different text\n"})
                with self.subTest(member=member), self.assertRaisesRegex(checker.ReleaseApkError, "does not match root"):
                    self.check()
        self.run.assert_not_called()

    def test_updated_root_notices_must_be_present_in_full(self) -> None:
        member = "assets/legal/THIRD_PARTY_NOTICES.md"
        updated = self.members[member] + b"Apache License, Version 2.0\nadditional license paragraphs\n"
        (self.root / "THIRD_PARTY_NOTICES.md").write_bytes(updated)
        with self.assertRaisesRegex(checker.ReleaseApkError, "does not match root THIRD_PARTY_NOTICES.md"):
            self.check()
        self.run.assert_not_called()
        self.write_apk({**self.members, member: updated})
        self.assertEqual(self.check().package_name, checker.EXPECTED_PACKAGE)

    def test_rejects_stale_full_notices_and_newline_only_differences(self) -> None:
        member = "assets/legal/THIRD_PARTY_NOTICES.md"
        original = self.members[member]
        for changed in (original + b"stale additional text\n", original.replace(b"\n", b"\r\n")):
            self.write_apk({**self.members, member: changed})
            with self.subTest(changed=changed), self.assertRaisesRegex(checker.ReleaseApkError, "does not match root THIRD_PARTY_NOTICES.md"):
                self.check()
        self.run.assert_not_called()

    def test_rejects_missing_or_empty_root_legal_source(self) -> None:
        source = self.root / "NOTICE"
        source.unlink()
        with self.assertRaisesRegex(checker.ReleaseApkError, "ZIP/legal assets"):
            self.check()
        source.write_bytes(b"")
        with self.assertRaisesRegex(checker.ReleaseApkError, "Root legal document is empty"):
            self.check()
        self.run.assert_not_called()

    def test_tool_discovery_uses_only_pinned_build_tools_and_environment_fallback(self) -> None:
        home, fallback = self.root / "SDK home", self.root / "SDK fallback"
        binary = self.tool.name
        latest = home / "build-tools" / "latest" / binary
        latest.parent.mkdir(parents=True)
        latest.write_bytes(b"not the pinned version")
        pinned = fallback / "build-tools" / checker.BUILD_TOOLS_VERSION / binary
        pinned.parent.mkdir(parents=True)
        pinned.write_bytes(b"mock tool")
        with patch.dict(os.environ, {"ANDROID_HOME": str(home), "ANDROID_SDK_ROOT": str(fallback)}):
            self.assertEqual(checker.find_aapt2(), pinned.resolve())
            home_pinned = home / "build-tools" / checker.BUILD_TOOLS_VERSION / binary
            home_pinned.parent.mkdir(parents=True)
            home_pinned.write_bytes(b"mock home tool")
            self.assertEqual(checker.find_aapt2(), home_pinned.resolve())
            self.assertEqual(checker.find_aapt2(self.tool), self.tool.resolve())

    def test_missing_explicit_tool_does_not_fall_back(self) -> None:
        with patch.dict(os.environ, {"ANDROID_HOME": str(self.root)}):
            with self.assertRaisesRegex(checker.ReleaseApkError, "Explicit aapt2 file"):
                checker.find_aapt2(self.root / "missing aapt2")

    def test_cli_succeeds_with_an_explicit_tool(self) -> None:
        result, output, errors = self.cli("--aapt2", str(self.tool))
        self.assertEqual(result, 0, errors)
        self.assertIn("Release APK policy: OK", output)
        self.assertEqual(errors, "")

    def test_cli_returns_nonzero_for_a_broken_zip(self) -> None:
        self.apk.write_bytes(b"broken ZIP")
        result, output, errors = self.cli("--aapt2", str(self.tool))
        self.assertEqual(result, 1)
        self.assertEqual(output, "")
        self.assertIn("ZIP", errors)
        self.run.assert_not_called()

    def test_cli_requires_an_apk_argument(self) -> None:
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            checker.main([])
        self.assertEqual(error.exception.code, 2)
        self.run.assert_not_called()

    def test_cli_fails_when_no_pinned_tool_can_be_found(self) -> None:
        result, output, errors = self.cli()
        self.assertEqual(result, 1)
        self.assertEqual(output, "")
        self.assertIn("--aapt2", errors)
        self.assertIn("36.0.0", errors)
        self.run.assert_not_called()

    def test_cli_fails_on_tool_execution_errors(self) -> None:
        for error in (
            FileNotFoundError("tool disappeared"),
            PermissionError("not executable"),
            subprocess.TimeoutExpired([str(self.tool)], checker.TOOL_TIMEOUT_SECONDS),
            UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid tool output"),
        ):
            self.run.side_effect = error
            with self.subTest(error=error):
                result, output, errors = self.cli("--aapt2", str(self.tool))
                self.assertEqual(result, 1)
                self.assertEqual(output, "")
                self.assertIn("aapt2 dump badging failed", errors)

    def test_cli_rejects_nonzero_empty_and_unparseable_tool_results(self) -> None:
        for command in ("badging", "permissions"):
            for result in (
                subprocess.CompletedProcess([], 1, BADGING if command == "badging" else PERMISSIONS, "tool error"),
                subprocess.CompletedProcess([], 0, "  \n", ""),
                subprocess.CompletedProcess([], 0, "unparseable tool output", ""),
            ):
                self.run.side_effect = ([subprocess.CompletedProcess([], 0, BADGING, "")] if command == "permissions" else []) + [result]
                with self.subTest(command=command, result=result):
                    status, output, errors = self.cli("--aapt2", str(self.tool))
                    self.assertEqual(status, 1)
                    self.assertEqual(output, "")
                    self.assertTrue(errors.startswith("ERROR:"), errors)


if __name__ == "__main__":
    unittest.main()
