import json
import re
import shlex
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class CiToolchainTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.toolchain = json.loads((ROOT / "gradle/toolchain.json").read_text(encoding="utf-8"))
        cls.workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    def test_ci_jobs_use_the_pinned_jdk(self) -> None:
        # Adoptium encodes a Java interim patch in its SemVer build number:
        # runtime 17.0.20.1+1 is published as 17.0.20+101. setup-java requires
        # that SemVer, not the four-component Java runtime version.
        runtime, build = self.toolchain["jdkVersion"].split("+", 1)
        components = runtime.split(".")
        patch = int(components[3]) if len(components) == 4 else 0
        expected = f'{".".join(components[:3])}+{patch * 100 + int(build)}'
        versions = re.findall(r"^\s+java-version:\s*'([^']+)'\s*(?:#.*)?$", self.workflow, re.MULTILINE)
        self.assertEqual(versions, [expected] * 2)

    def test_ci_jobs_use_the_pinned_command_line_tools(self) -> None:
        filename = self.toolchain["androidCommandLineTools"]["filename"]
        match = re.fullmatch(r"commandlinetools-linux-(\d+)_latest\.zip", filename)
        self.assertIsNotNone(match, filename)
        versions = re.findall(r"^\s+cmdline-tools-version:\s*'(\d+)'\s*$", self.workflow, re.MULTILINE)
        self.assertEqual(versions, [match.group(1)] * 2)

    def test_ci_jobs_install_the_pinned_sdk_packages(self) -> None:
        commands = re.findall(r"^\s+run:\s+sdkmanager\s+(.+)$", self.workflow, re.MULTILINE)
        self.assertEqual([shlex.split(command) for command in commands], [self.toolchain["sdkPackages"]] * 2)

    def test_app_uses_the_installed_build_tools(self) -> None:
        packages = self.toolchain["sdkPackages"]
        versions = [package.split(";", 1)[1] for package in packages if package.startswith("build-tools;")]
        self.assertEqual(len(versions), 1)
        build_script = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(f'buildToolsVersion = "{versions[0]}"', build_script)


if __name__ == "__main__":
    unittest.main()
