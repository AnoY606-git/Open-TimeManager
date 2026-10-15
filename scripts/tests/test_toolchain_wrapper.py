import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / "scripts/with-toolchain.sh"
README_ADB_COMMANDS = (
    "scripts/with-toolchain.sh adb devices",
    "scripts/with-toolchain.sh adb install -r app/build/outputs/apk/debug/app-debug.apk",
)


class ToolchainWrapperTest(unittest.TestCase):
    def test_exposes_bootstrapped_platform_tools_before_existing_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            java_home = temporary / "jdk"
            android_home = temporary / "android-sdk"
            (java_home / "bin").mkdir(parents=True)
            (android_home / "platform-tools").mkdir(parents=True)
            java = java_home / "bin/java"
            java.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            java.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "OTM_JAVA_HOME": str(java_home),
                    "OTM_ANDROID_HOME": str(android_home),
                    "OTM_ANDROID_USER_HOME": str(temporary / "android-user"),
                    "OTM_GRADLE_USER_HOME": str(temporary / "gradle-home"),
                    "PATH": "/usr/bin:/bin",
                },
            )

            result = subprocess.run(
                [str(WRAPPER), "sh", "-c", 'printf "%s" "$PATH"'],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(
                result.stdout.split(os.pathsep)[:2],
                [str(java_home / "bin"), str(android_home / "platform-tools")],
            )

    def test_both_readmes_use_the_toolchain_wrapper_for_adb(self) -> None:
        for relative in ("README.md", "README.en.md"):
            with self.subTest(readme=relative):
                contents = (ROOT / relative).read_text(encoding="utf-8")
                for command in README_ADB_COMMANDS:
                    self.assertIn(command, contents)
                self.assertNotIn("\nadb devices\n", contents)
                self.assertNotIn("\nadb install -r ", contents)


if __name__ == "__main__":
    unittest.main()
