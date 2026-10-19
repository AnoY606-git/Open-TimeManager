import hashlib
import json
import os
import re
import shlex
import subprocess
import tempfile
import textwrap
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

    def test_ci_limits_gradle_tasks_to_debug_checks(self) -> None:
        # Общие build/check и release-задачи снова включили бы стабильную сборку в CI.
        tasks = set(re.findall(r":(?:core|app):\w+", self.workflow))
        self.assertEqual(tasks, {
            ":core:test",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
            ":app:compileDebugAndroidTestKotlin",
            ":app:assembleDebug",
            ":app:connectedDebugAndroidTest",
        })
        self.assertNotIn("release", self.workflow.lower())

    @unittest.skipUnless(os.name == "posix", "CI checksum commands require a POSIX environment")
    def test_ci_packages_debug_apk_with_checksums_and_source_commit(self) -> None:
        step = re.search(
            r"      - name: Record APK checksums and source commit\n"
            r"        working-directory: app/build/outputs/apk\n"
            r"        run: \|\n((?:          .*\n)+)",
            self.workflow,
        )
        self.assertIsNotNone(step, "Missing APK provenance step")
        git_dir = subprocess.check_output(
            ["git", "rev-parse", "--absolute-git-dir"], cwd=ROOT, text=True,
        ).strip()
        source_commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True,
        ).strip()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk_root = root / "app/build/outputs/apk"
            debug = apk_root / "debug/OTM.apk"
            debug.parent.mkdir(parents=True)
            contents = b"debug APK fixture"
            debug.write_bytes(contents)
            # Старая локальная сборка не должна попасть в новый артефакт через wildcard.
            (debug.parent / "app-debug.apk").write_bytes(b"stale debug APK")
            result = subprocess.run(
                ["bash", "-eu", "-c", textwrap.dedent(step.group(1))],
                cwd=apk_root, env={**os.environ, "GIT_DIR": git_dir},
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                (apk_root / "metadata/SHA256SUMS").read_text(encoding="utf-8"),
                f"{hashlib.sha256(contents).hexdigest()}  debug/OTM.apk\n",
            )
            self.assertEqual(
                (apk_root / "metadata/SOURCE_COMMIT.txt").read_text(encoding="utf-8"),
                source_commit + "\n",
            )
            uploads = re.findall(
                r"          path: \|\n((?:            .*\n)+)", self.workflow,
            )
            apk_uploads = [block for block in uploads if "app/build/outputs/apk/debug/" in block]
            self.assertEqual(len(apk_uploads), 1, "Missing debug APK upload")
            uploaded = set()
            for pattern in textwrap.dedent(apk_uploads[0]).splitlines():
                matches = list(root.glob(pattern))
                self.assertTrue(matches, f"Missing artifact: {pattern}")
                uploaded.update(path.relative_to(apk_root).as_posix() for path in matches)
            self.assertEqual(uploaded, {
                "debug/OTM.apk",
                "metadata/SHA256SUMS",
                "metadata/SOURCE_COMMIT.txt",
            })


if __name__ == "__main__":
    unittest.main()
