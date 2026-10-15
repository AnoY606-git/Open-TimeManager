import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check_repository.py"
PINNED_SHA = "0123456789abcdef0123456789abcdef01234567"


class RepositoryPolicyTest(unittest.TestCase):
    """Each case mutates one otherwise-valid repository fixture."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self._write_valid_fixture()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_checker(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER), "--root", str(self.root)],
            text=True,
            capture_output=True,
            check=False,
        )

    def assert_rejected(self, expected_fragment: str) -> None:
        result = self.run_checker()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(expected_fragment, result.stdout + result.stderr)

    def test_accepts_minimal_repository_that_satisfies_every_policy(self) -> None:
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_tracked_private_material_and_secrets(self) -> None:
        for path, contents, expected in (
            ("local.properties", "sdk.dir=/private/sdk\n", "local.properties"),
            ("release.jks", "not-a-real-key", "release.jks"),
            (".toolchain/jdk/bin/java", "binary", ".toolchain"),
            ("AGENTS.md", "private agent instructions", "AGENTS.md"),
            ("docs/superpowers/specs/private.md", "private", "docs/superpowers"),
            ("notes.env", "API_KEY=" + "ghp_" + "a" * 36 + "\n", "secret"),
        ):
            with self.subTest(path=path):
                candidate = self.root / path
                candidate.parent.mkdir(parents=True, exist_ok=True)
                candidate.write_text(contents, encoding="utf-8")
                self._git("add", "-f", path)
                self.assert_rejected(expected)
                self._git("reset", "--", path)
                candidate.unlink()

    def test_rejects_missing_russian_translation_key(self) -> None:
        (self.root / "app/src/main/res/values-ru/strings.xml").write_text(
            "<resources><string name=\"app_name\">Open TimeManager</string></resources>\n",
            encoding="utf-8",
        )
        self._git("add", "app/src/main/res/values-ru/strings.xml")
        self.assert_rejected("translation")

    def test_rejects_mutable_github_action_reference(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text("steps:\n  - uses: actions/checkout@v4\n", encoding="utf-8")
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("full commit SHA")

    def test_rejects_mutable_github_action_with_quoted_key(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text('steps:\n  - "uses": actions/checkout@v4\n', encoding="utf-8")
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("full commit SHA")

    def test_rejects_mutable_github_action_with_spaced_separator(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text("steps:\n  - uses : actions/checkout@v4\n", encoding="utf-8")
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("full commit SHA")

    def test_rejects_mutable_github_action_in_flow_mapping(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text("steps:\n  - { name: Checkout, uses: actions/checkout@v4 }\n", encoding="utf-8")
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("full commit SHA")

    def test_accepts_full_sha_in_quoted_spaced_and_flow_action_references(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            f'steps:\n  - "uses" : "actions/checkout@{PINNED_SHA}"\n'
            f"  - {{ uses: actions/setup-java@{PINNED_SHA}, name: JDK }}\n",
            encoding="utf-8",
        )
        self._git("add", ".github/workflows/ci.yml")
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_multiline_action_reference_that_cannot_be_safely_parsed(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text("steps:\n  - uses: >\n      actions/checkout@v4\n", encoding="utf-8")
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("could not be parsed safely")

    def test_rejects_setup_android_obsolete_default_packages(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            f"steps:\n  - uses: android-actions/setup-android@{PINNED_SHA}\n",
            encoding="utf-8",
        )
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("override setup-android packages")

    def test_accepts_setup_android_with_explicit_empty_packages(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            f"steps:\n  - uses: android-actions/setup-android@{PINNED_SHA}\n"
            "    with:\n"
            "      packages: ''\n",
            encoding="utf-8",
        )
        self._git("add", ".github/workflows/ci.yml")
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_emulator_runner_without_kvm_permissions(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            f"steps:\n  - uses: reactivecircus/android-emulator-runner@{PINNED_SHA}\n",
            encoding="utf-8",
        )
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("enable KVM before android-emulator-runner")

    def test_accepts_emulator_runner_after_kvm_permissions(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            "steps:\n"
            "  - name: Enable KVM group permissions\n"
            "    run: |\n"
            "      echo 'KERNEL==\"kvm\", GROUP=\"kvm\", MODE=\"0666\", "
            "OPTIONS+=\"static_node=kvm\"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules\n"
            "      sudo udevadm control --reload-rules\n"
            "      sudo udevadm trigger --name-match=kvm\n"
            f"  - uses: reactivecircus/android-emulator-runner@{PINNED_SHA}\n"
            "    with:\n"
            "      disk-size: 2G\n",
            encoding="utf-8",
        )
        self._git("add", ".github/workflows/ci.yml")
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_emulator_runner_without_bounded_disk_size(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            "steps:\n"
            "  - name: Enable KVM group permissions\n"
            "    run: |\n"
            "      echo OPTIONS+=\"static_node=kvm\"\n"
            "      sudo udevadm trigger --name-match=kvm\n"
            f"  - uses: reactivecircus/android-emulator-runner@{PINNED_SHA}\n",
            encoding="utf-8",
        )
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("bounded emulator disk size")

    def test_rejects_network_and_broad_android_permissions(self) -> None:
        manifest = self.root / "app/src/main/AndroidManifest.xml"
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<uses-permission android:name="android.permission.INTERNET" />'
            '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />'
            "</manifest>",
            encoding="utf-8",
        )
        self._git("add", "app/src/main/AndroidManifest.xml")
        self.assert_rejected("forbidden Android permission")

    def test_rejects_dynamic_catalog_version(self) -> None:
        catalog = self.root / "gradle/libs.versions.toml"
        catalog.write_text('[versions]\nkotlin = "+"\n', encoding="utf-8")
        self._git("add", "gradle/libs.versions.toml")
        self.assert_rejected("dynamic dependency version")

    def test_rejects_dynamic_catalog_inline_library_version(self) -> None:
        catalog = self.root / "gradle/libs.versions.toml"
        catalog.write_text(
            '[versions]\nkotlin = "2.2.21"\n'
            '[libraries]\nexample = { module = "x:y", version = "+" }\n',
            encoding="utf-8",
        )
        self._git("add", "gradle/libs.versions.toml")
        self.assert_rejected("dynamic dependency version")

    def test_rejects_dynamic_nested_versions_table(self) -> None:
        catalog = self.root / "gradle/libs.versions.toml"
        catalog.write_text(
            '[versions]\nkotlin = "2.2.21"\n'
            '[versions.release]\nchannel = "latest.integration"\n',
            encoding="utf-8",
        )
        self._git("add", "gradle/libs.versions.toml")
        self.assert_rejected("dynamic dependency version")

    def test_rejects_dynamic_catalog_recursive_version_constraint(self) -> None:
        catalog = self.root / "gradle/libs.versions.toml"
        catalog.write_text(
            '[versions]\nkotlin = "2.2.21"\n'
            '[libraries.example]\nmodule = "x:y"\n'
            'version = { strictly = "1.+", prefer = "1.2.3" }\n',
            encoding="utf-8",
        )
        self._git("add", "gradle/libs.versions.toml")
        self.assert_rejected("dynamic dependency version")

    def test_rejects_dynamic_string_dependency_coordinate(self) -> None:
        build = self.root / "app/build.gradle.kts"
        build.parent.mkdir(parents=True, exist_ok=True)
        build.write_text('dependencies { implementation("example:unsafe:+") }\n', encoding="utf-8")
        self._git("add", "app/build.gradle.kts")
        self.assert_rejected("dynamic dependency version")

    def test_rejects_dynamic_quoted_gradle_plugin_version(self) -> None:
        build = self.root / "app/build.gradle.kts"
        build.parent.mkdir(parents=True, exist_ok=True)
        build.write_text('plugins { id("example.plugin") version "latest.release" }\n', encoding="utf-8")
        self._git("add", "app/build.gradle.kts")
        self.assert_rejected("dynamic dependency version")

    def test_accepts_dynamic_dependency_examples_inside_gradle_comments(self) -> None:
        build = self.root / "app/build.gradle.kts"
        build.parent.mkdir(parents=True, exist_ok=True)
        build.write_text(
            '// Do not use implementation("example:unsafe:+")\n'
            '/* Do not set version = "latest.release". */\n'
            'dependencies { implementation("example:safe:1.2.3") }\n',
            encoding="utf-8",
        )
        self._git("add", "app/build.gradle.kts")
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_missing_packaged_legal_asset(self) -> None:
        path = self.root / "app/src/main/assets/legal/LICENSE.txt"
        path.unlink()
        self._git("add", "-u")
        self.assert_rejected("legal asset")

    def test_rejects_missing_packaged_attribution_asset(self) -> None:
        path = self.root / "app/src/main/assets/legal/ATTRIBUTION.md"
        path.unlink()
        self._git("add", "-u")
        self.assert_rejected("legal asset")

    def test_rejects_stale_packaged_legal_asset(self) -> None:
        path = self.root / "app/src/main/assets/legal/NOTICE.txt"
        path.write_text("old notice\n", encoding="utf-8")
        self._git("add", path.relative_to(self.root).as_posix())
        self.assert_rejected("does not match")

    def test_rejects_incomplete_release_runtime_legal_inventory(self) -> None:
        path = self.root / "THIRD_PARTY_NOTICES.md"
        path.write_text("AndroidX only\n", encoding="utf-8")
        packaged = self.root / "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md"
        packaged.write_text("AndroidX only\n", encoding="utf-8")
        self._git("add", "THIRD_PARTY_NOTICES.md", packaged.relative_to(self.root).as_posix())
        self.assert_rejected("runtime legal notice")

    def test_rejects_untracked_room_schema(self) -> None:
        path = self.root / "app/schemas/example/2.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{}", encoding="utf-8")
        self.assert_rejected("untracked Room schema")

    def test_rejects_repository_with_all_room_schemas_deleted(self) -> None:
        path = self.root / "app/schemas/example/1.json"
        path.unlink()
        self._git("add", "-u")
        self.assert_rejected("tracked Room schema")

    def _write_valid_fixture(self) -> None:
        files = {
            "app/src/main/res/values/strings.xml": (
                '<resources><string name="app_name">Open TimeManager</string>'
                '<string name="legal">Legal</string></resources>\n'
            ),
            "app/src/main/res/values-ru/strings.xml": (
                '<resources><string name="app_name">Open TimeManager</string>'
                '<string name="legal">Правовая информация</string></resources>\n'
            ),
            "app/src/main/AndroidManifest.xml": (
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />'
                "</manifest>\n"
            ),
            "gradle/libs.versions.toml": '[versions]\nkotlin = "2.2.21"\n',
            ".github/workflows/ci.yml": f"steps:\n  - uses: actions/checkout@{PINNED_SHA}\n",
            "LICENSE": "GNU GENERAL PUBLIC LICENSE Version 3\n",
            "NOTICE": "Open TimeManager\n",
            "AUTHORS.md": "# Authors\n",
            "ATTRIBUTION.md": "# Attribution\n",
            "THIRD_PARTY_NOTICES.md": (
                "# Third-party notices\n"
                "AndroidX\nOkio\nGuava ListenableFuture\nJSpecify\n"
                "datastore-preferences-external-protobuf\nBSD-3-Clause\n"
            ),
            "app/src/main/assets/legal/LICENSE.txt": "GNU GENERAL PUBLIC LICENSE Version 3\n",
            "app/src/main/assets/legal/NOTICE.txt": "Open TimeManager\n",
            "app/src/main/assets/legal/ATTRIBUTION.md": "# Attribution\n",
            "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md": (
                "# Third-party notices\n"
                "AndroidX\nOkio\nGuava ListenableFuture\nJSpecify\n"
                "datastore-preferences-external-protobuf\nBSD-3-Clause\n"
            ),
            "app/schemas/example/1.json": "{}\n",
        }
        for relative, contents in files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
        self._git("init", "-q")
        self._git("add", ".")

    def _git(self, *args: str) -> None:
        subprocess.run(
            ["git", "-C", str(self.root), *args],
            check=True,
            text=True,
            capture_output=True,
        )


if __name__ == "__main__":
    unittest.main()
