import os
import shutil
import subprocess
import tempfile
import unittest
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / "scripts/with-toolchain.sh"
DOCUMENTED_ADB_COMMANDS = (
    "scripts/with-toolchain.sh adb devices -l",
    "scripts/with-toolchain.sh adb -s SERIAL install -r app/build/outputs/apk/debug/OTM.apk",
)


class ToolchainWrapperDocumentationTest(unittest.TestCase):
    def test_both_readmes_link_to_the_build_guide(self) -> None:
        for relative in ("README.md", "README.en.md"):
            with self.subTest(readme=relative):
                contents = (ROOT / relative).read_text(encoding="utf-8")
                self.assertIn("docs/BUILDING.md", contents)

    def test_both_build_guide_languages_use_the_toolchain_wrapper_for_adb(self) -> None:
        guide = (ROOT / "docs/BUILDING.md").read_text(encoding="utf-8")
        russian, english = guide.split("\n## English\n", 1)
        for language, contents in (("ru", russian), ("en", english)):
            with self.subTest(language=language):
                for command in DOCUMENTED_ADB_COMMANDS:
                    self.assertIn(command, contents)
                self.assertNotIn("\nadb devices", contents)
                self.assertNotIn("\nadb install -r ", contents)
                self.assertNotIn("\nadb -s SERIAL install -r ", contents)


@unittest.skipUnless(os.name == "posix", "with-toolchain.sh requires a POSIX environment")
class ToolchainWrapperTest(unittest.TestCase):
    def test_complete_bootstrap_toolchain_precedes_standard_environment(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            local_java, local_android = self.create_toolchain(root / ".toolchain", "jdk", "android-sdk")
            system_java, system_android = self.create_toolchain(temporary / "system", "jdk", "android-sdk")

            selected = self.run_selection(
                root,
                temporary,
                JAVA_HOME=str(system_java),
                ANDROID_HOME=str(system_android),
            )

            self.assert_selection(selected, local_java, local_android)

    def test_explicit_otm_toolchain_overrides_bootstrap_and_standard_environment(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            self.create_toolchain(root / ".toolchain", "jdk", "android-sdk")
            system_java, system_android = self.create_toolchain(temporary / "system", "jdk", "android-sdk")
            otm_java, otm_android = self.create_toolchain(temporary / "override", "jdk", "android-sdk")

            selected = self.run_selection(
                root,
                temporary,
                OTM_JAVA_HOME=str(otm_java),
                OTM_ANDROID_HOME=str(otm_android),
                JAVA_HOME=str(system_java),
                ANDROID_HOME=str(system_android),
            )

            self.assert_selection(selected, otm_java, otm_android)

    def test_standard_java_home_and_android_home_work_without_local_toolchain(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            system_java, system_android = self.create_toolchain(temporary / "system", "jdk", "android-sdk")

            selected = self.run_selection(
                root,
                temporary,
                JAVA_HOME=str(system_java),
                ANDROID_HOME=str(system_android),
            )

            self.assertFalse((root / ".toolchain/jdk").exists())
            self.assert_selection(selected, system_java, system_android)

    def test_android_sdk_root_is_used_when_android_home_is_not_valid(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            system_java, system_android = self.create_toolchain(temporary / "system", "jdk", "android-sdk")

            selected = self.run_selection(
                root,
                temporary,
                JAVA_HOME=str(system_java),
                ANDROID_HOME=str(temporary / "missing-android-home"),
                ANDROID_SDK_ROOT=str(system_android),
            )

            self.assert_selection(selected, system_java, system_android)

    def test_missing_toolchain_error_explains_supported_jdk_choices(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            selected = self.run_selection(root, temporary)

            self.assertEqual(selected[0], "ERROR", selected)
            self.assertIn("scripts/bootstrap.py", selected[2])
            self.assertIn("OTM_JAVA_HOME", selected[2])
            self.assertIn("JAVA_HOME", selected[2])

    def test_standard_user_homes_are_preserved_without_otm_overrides(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            self.create_toolchain(root / ".toolchain", "jdk", "android-sdk")
            android_user = temporary / "shared android user"
            gradle_user = temporary / "shared gradle user"

            selected = self.run_selection(
                root,
                temporary,
                OTM_ANDROID_USER_HOME="",
                OTM_GRADLE_USER_HOME="",
                ANDROID_USER_HOME=str(android_user),
                GRADLE_USER_HOME=str(gradle_user),
            )

            self.assertEqual(selected[4:], [str(android_user), str(gradle_user)])
            self.assertTrue(android_user.is_dir())
            self.assertTrue(gradle_user.is_dir())

    def test_otm_user_homes_override_standard_user_homes(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            self.create_toolchain(root / ".toolchain", "jdk", "android-sdk")
            selected = self.run_selection(
                root,
                temporary,
                ANDROID_USER_HOME=str(temporary / "other-android-user"),
                GRADLE_USER_HOME=str(temporary / "other-gradle-user"),
            )

            self.assertEqual(selected[4:], [str(temporary / "android-user"), str(temporary / "gradle-home")])

    def test_user_homes_default_to_local_toolchain(self) -> None:
        with self.isolated_wrapper() as (root, temporary):
            self.create_toolchain(root / ".toolchain", "jdk", "android-sdk")
            selected = self.run_selection(root, temporary, OTM_ANDROID_USER_HOME="", OTM_GRADLE_USER_HOME="")

            self.assertEqual(
                selected[4:],
                [str(root / ".toolchain/android-user"), str(root / ".toolchain/gradle-home")],
            )

    @staticmethod
    def create_toolchain(parent: Path, java_name: str, android_name: str) -> tuple[Path, Path]:
        java_home = parent / java_name
        android_home = parent / android_name
        ToolchainWrapperTest.write_executable(java_home / "bin/java")
        ToolchainWrapperTest.write_executable(android_home / "platform-tools/adb")
        return java_home, android_home

    @staticmethod
    def write_executable(path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        path.chmod(0o755)

    @staticmethod
    @contextmanager
    def isolated_wrapper() -> Iterator[tuple[Path, Path]]:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            root = temporary / "repository"
            scripts = root / "scripts"
            scripts.mkdir(parents=True)
            shutil.copy2(WRAPPER, scripts / WRAPPER.name)
            yield root, temporary

    @staticmethod
    def run_selection(root: Path, temporary: Path, **overrides: str) -> list[str]:
        environment = os.environ.copy()
        for name in (
            "OTM_JAVA_HOME",
            "OTM_ANDROID_HOME",
            "OTM_ANDROID_USER_HOME",
            "OTM_GRADLE_USER_HOME",
            "JAVA_HOME",
            "ANDROID_HOME",
            "ANDROID_SDK_ROOT",
            "ANDROID_USER_HOME",
            "GRADLE_USER_HOME",
        ):
            environment.pop(name, None)
        environment.update(
            {
                "OTM_ANDROID_USER_HOME": str(temporary / "android-user"),
                "OTM_GRADLE_USER_HOME": str(temporary / "gradle-home"),
                "PATH": "/usr/bin:/bin",
                **overrides,
            },
        )
        result = subprocess.run(
            [
                str(root / "scripts/with-toolchain.sh"),
                "sh",
                "-c",
                'printf "%s\\n" "$JAVA_HOME" "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$PATH" "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"',
            ],
            cwd=root,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            return ["ERROR", result.stdout, result.stderr]
        return result.stdout.splitlines()

    def assert_selection(self, selected: list[str], java_home: Path, android_home: Path) -> None:
        self.assertEqual(len(selected), 6, selected)
        self.assertEqual(selected[:3], [str(java_home), str(android_home), str(android_home)])
        self.assertEqual(
            selected[3].split(os.pathsep)[:2],
            [str(java_home / "bin"), str(android_home / "platform-tools")],
        )


if __name__ == "__main__":
    unittest.main()
