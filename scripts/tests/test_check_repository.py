import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_repository.py"
PINNED_SHA = "0123456789abcdef0123456789abcdef01234567"
# Reuse the shipped text rather than keeping another full license copy in tests.
RUNTIME_NOTICES = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
ENVIRONMENT_FILES = (".env", ".env.local", ".env.example", "config/.env", "module/config/.env.release")
PRIVATE_CONFIG_FILES = (
    "signing.properties", "module/config/signing.properties",
    "AGENTS.md", "RESEARCH.md", "module/docs/AGENTS.md", "module/docs/RESEARCH.md",
)
SIGNING_FILES = tuple(
    f"{prefix}release{suffix}"
    for prefix in ("", "module/keys/")
    for suffix in (".pem", ".key", ".jks", ".keystore", ".p12", ".pfx")
)
INTERNAL_FILES = tuple(
    f"{prefix}{directory}/private.txt"
    for prefix in ("", "nested/module/")
    for directory in (
        ".agents", ".codex", ".claude", ".signing", ".superpowers",
        ".gradle", ".kotlin", ".toolchain", "plans", "specs",
        "docs/internal", "docs/plans", "docs/specs", "docs/superpowers",
    )
) + ("docs/internal/BUILDING.md", "module/docs/internal/ARCHITECTURE.md", "module/docs/specs/TESTING.md")
USER_BACKUP_FILES = (
    "work.otmbackup", "module/exports/work.otmbackup",
    "work.otmplain", "module/exports/work.otmplain", "exports/work.OTMPLAIN",
)
ANDROID_OUTPUT_FILES = ("app.apk", "app.aab", "distribution/nested/app.apk", "module/releases/app.aab")
PUBLIC_FILES = {
    **{
        f"{prefix}{name}": b"# Public developer documentation\n"
        for prefix in ("", "docs/", "module/docs/")
        for name in ("BUILDING.md", "ARCHITECTURE.md", "TESTING.md")
    },
    "docs/RELEASING.md": b"# Release checklist\n",
    "docs/internal-overview.md": b"# Public overview\n",
    "gradle/wrapper/gradle-wrapper.jar": b"PK\x03\x04\x00\xff",
    "app/src/main/res/drawable/public-icon.png": b"\x89PNG\r\n\x1a\n\x00\xff",
    "core/src/test/resources/backup/golden-vector.bin": b"\x00\xff\x01\x02",
    "core/src/test/resources/backup/public-key.json": b'{"synthetic": true}\n',
    "app/src/main/kotlin/example/internal/Model.kt": b"package example.internal\n",
}


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

    def test_rejects_environment_files_at_any_depth(self) -> None:
        self.assert_paths_rejected(ENVIRONMENT_FILES, "environment credentials")

    def test_rejects_private_configuration_and_instruction_files_at_any_depth(self) -> None:
        self.assert_paths_rejected(PRIVATE_CONFIG_FILES, "private/development file")

    def test_rejects_signing_material_at_any_depth(self) -> None:
        self.assert_paths_rejected(SIGNING_FILES + ("module/keys/release.PEM",), "signing material")

    def test_rejects_internal_directories_at_any_depth(self) -> None:
        self.assert_paths_rejected(INTERNAL_FILES, "private/toolchain directory")

    def test_rejects_user_backups_at_any_depth(self) -> None:
        self.assert_paths_rejected(USER_BACKUP_FILES + ("exports/work.OTMBACKUP",), "private backup")

    def test_rejects_android_artifacts_outside_build_directories(self) -> None:
        self.assert_paths_rejected(ANDROID_OUTPUT_FILES + ("exports/app.APK",), "Android build artifact")

    def test_accepts_public_developer_docs_and_binary_assets(self) -> None:
        for relative, contents in PUBLIC_FILES.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(contents)
            self._git("add", "-f", relative)
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_gitignore_covers_private_paths_at_any_depth(self) -> None:
        paths = (
            ENVIRONMENT_FILES + PRIVATE_CONFIG_FILES + SIGNING_FILES + INTERNAL_FILES
            + USER_BACKUP_FILES + ANDROID_OUTPUT_FILES
        )
        result = self.check_ignored_paths(paths)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(set(result.stdout.decode("utf-8").split("\0")) - {""}, set(paths))

    def test_gitignore_keeps_public_developer_docs_and_assets_visible(self) -> None:
        result = self.check_ignored_paths(tuple(PUBLIC_FILES))
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(result.stdout, b"")

    def test_gitattributes_enforces_lf_bat_crlf_and_binary_jar(self) -> None:
        (self.root / ".gitattributes").write_bytes((ROOT / ".gitattributes").read_bytes())
        paths = (
            "README.md", "scripts/example.py", "gradlew", "gradlew.bat", "tools/install.bat",
            "gradle/wrapper/gradle-wrapper.jar",
        )
        result = subprocess.run(
            ["git", "-C", str(self.root), "check-attr", "-z", "--stdin", "text", "eol"],
            input=("\0".join(paths) + "\0").encode("utf-8"),
            capture_output=True,
            check=True,
        )
        fields = result.stdout.decode("utf-8").split("\0")[:-1]
        attributes = {
            (name, attribute): value
            for name, attribute, value in zip(fields[0::3], fields[1::3], fields[2::3])
        }
        for relative in paths[:3]:
            self.assertEqual(attributes[(relative, "eol")], "lf")
        for relative in paths[3:5]:
            self.assertEqual(attributes[(relative, "eol")], "crlf")
        self.assertEqual(attributes[("README.md", "text")], "auto")
        self.assertEqual(attributes[("gradle/wrapper/gradle-wrapper.jar", "text")], "unset")

    def test_rejects_missing_russian_translation_key(self) -> None:
        (self.root / "app/src/main/res/values-ru/strings.xml").write_text(
            "<resources><string name=\"app_name\">Open TimeManager</string></resources>\n",
            encoding="utf-8",
        )
        self._git("add", "app/src/main/res/values-ru/strings.xml")
        self.assert_rejected("translation")

    def test_accepts_translation_names_aggregated_across_differently_named_xml_files(self) -> None:
        self.write_resource("values/workflow.xml", '<string name="help">Guide %1$s</string>')
        self.write_resource("values-ru/platform.xml", '<string name="help">Руководство %1$s</string>')
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_missing_translation_from_any_resource_xml(self) -> None:
        for name in ("workflow", "platform", "ux_refinements"):
            with self.subTest(file=name):
                self.write_resource(f"values/{name}.xml", f'<string name="{name}">English</string>')
                self.assert_rejected(f"Russian translation is missing: string/{name}")
                self.write_resource(f"values-ru/{name}.xml", f'<string name="{name}">Русский</string>')
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_translation_without_a_source_key_outside_strings_xml(self) -> None:
        self.write_resource("values-ru/workflow.xml", '<string name="orphan">Нет оригинала</string>')
        self.assert_rejected("English translation is missing: string/orphan")

    def test_does_not_require_translations_for_theme_xml_or_nontranslatable_resources(self) -> None:
        self.write_resource("values/themes.xml", '<style name="Theme.App">'
                            '<item name="android:windowLightStatusBar">true</item></style>')
        self.write_resource("values/constants.xml", '<string name="protocol" translatable="false">OTMP1</string>'
                            '<plurals name="fixed_count" translatable="false"><item quantity="other">%d</item></plurals>'
                            '<string-array name="fixed_labels" translatable="false"><item>Protocol</item></string-array>')
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_duplicate_translation_names_within_or_across_xml_files(self) -> None:
        for folder in ("values", "values-ru"):
            with self.subTest(folder=folder):
                self.write_resource(f"{folder}/duplicates.xml", '<string name="legal">Duplicate</string>')
                self.assert_rejected("duplicate translation resource: string/legal")
                self.write_resource(f"{folder}/duplicates.xml", '<string name="inside">One</string><string name="inside">Two</string>')
                self.assert_rejected("duplicate translation resource: string/inside")
                self.write_resource(f"{folder}/duplicates.xml", "")

    def test_rejects_malformed_xml_outside_strings_xml(self) -> None:
        self.write_resource("values/workflow.xml", '<string name="broken">')
        self.assert_rejected("translation resource could not be parsed: app/src/main/res/values/workflow.xml")

    def test_rejects_changed_or_missing_format_arguments_in_extra_xml(self) -> None:
        self.write_resource("values/workflow.xml", '<string name="count">%1$s: %2$d%%</string>')
        for translated in ("%1$s: %2$s%%", "%1$s", "%1$s: %3$d%%"):
            with self.subTest(translated=translated):
                self.write_resource("values-ru/workflow.xml", f'<string name="count">{translated}</string>')
                self.assert_rejected("translation format arguments mismatch: string/count")

    def test_accepts_reordered_reused_and_literal_format_arguments(self) -> None:
        self.write_resource("values/workflow.xml", '<string name="count">%1$s: %2$d%%</string>'
                            '<string name="literal" formatted="false">%s is literal</string>')
        self.write_resource("values-ru/workflow.xml", '<string name="count">%2$d%%: %1$s (%1$s)</string>'
                            '<string name="literal" formatted="false">Буквальный %s</string>')
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_accepts_locale_specific_plural_quantities_and_string_arrays(self) -> None:
        self.write_resource("values/workflow.xml", '<plurals name="count"><item quantity="one">%d item</item>'
                            '<item quantity="other">%d items</item></plurals>'
                            '<string-array name="labels"><item>%1$s</item><item>Plain</item></string-array>')
        self.write_resource("values-ru/platform.xml", '<plurals name="count"><item quantity="one">%d запись</item>'
                            '<item quantity="few">%d записи</item><item quantity="many">%d записей</item>'
                            '<item quantity="other">%d записи</item></plurals>'
                            '<string-array name="labels"><item>%1$s</item><item>Текст</item></string-array>')
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_changed_plural_format_arguments(self) -> None:
        self.write_resource("values/workflow.xml", '<plurals name="count"><item quantity="other">%d items</item></plurals>')
        self.write_resource("values-ru/workflow.xml", '<plurals name="count"><item quantity="other">%s записей</item></plurals>')
        self.assert_rejected("translation format arguments mismatch: plurals/count[other]")

    def test_rejects_changed_string_array_length_and_format_arguments(self) -> None:
        self.write_resource("values/workflow.xml", '<string-array name="labels"><item>%1$d</item><item>Plain</item></string-array>')
        self.write_resource("values-ru/workflow.xml", '<string-array name="labels"><item>%1$s</item></string-array>')
        self.assert_rejected("translation array length mismatch: string-array/labels")
        self.assert_rejected("translation format arguments mismatch: string-array/labels[0]")

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
            "  - name: Free disk space for emulator\n"
            "    run: |\n"
            "      sudo rm -rf -- /usr/local/lib/android/sdk/ndk\n"
            "      df -h /\n"
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

    def test_rejects_emulator_runner_without_ndk_cleanup(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            "steps:\n"
            "  - name: Enable KVM group permissions\n"
            "    run: |\n"
            "      echo OPTIONS+=\"static_node=kvm\"\n"
            "      sudo udevadm trigger --name-match=kvm\n"
            f"  - uses: reactivecircus/android-emulator-runner@{PINNED_SHA}\n"
            "    with:\n"
            "      disk-size: 2G\n",
            encoding="utf-8",
        )
        self._git("add", ".github/workflows/ci.yml")
        self.assert_rejected("free the unused Android NDK")

    def test_rejects_emulator_runner_without_bounded_disk_size(self) -> None:
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(
            "steps:\n"
            "  - name: Free disk space for emulator\n"
            "    run: |\n"
            "      sudo rm -rf -- /usr/local/lib/android/sdk/ndk\n"
            "      df -h /\n"
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

    def test_rejects_url_only_apache_notice_even_when_assets_match(self) -> None:
        self.write_runtime_notices(
            "# Third-party notices\nAndroidX\nOkio\nGuava ListenableFuture\nJSpecify\n"
            "datastore-preferences-external-protobuf\nBSD-3-Clause\n"
            "Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0.txt\n"
        )
        self.assert_rejected("complete Apache-2.0 license text")

    def test_rejects_apache_text_missing_a_required_section_or_appendix(self) -> None:
        for marker in (
            "Version 2.0, January 2004",
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            "1. Definitions.", "2. Grant of Copyright License.", "3. Grant of Patent License.",
            "4. Redistribution.", "5. Submission of Contributions.", "6. Trademarks.",
            "7. Disclaimer of Warranty.", "8. Limitation of Liability.",
            "9. Accepting Warranty or Additional Liability.", "END OF TERMS AND CONDITIONS",
            "APPENDIX: How to apply the Apache License to your work.",
            "limitations under the License.",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, RUNTIME_NOTICES)
                self.write_runtime_notices(RUNTIME_NOTICES.replace(marker, "", 1))
                self.assert_rejected("complete Apache-2.0 license text")

    def test_accepts_complete_apache_text_with_wrapped_section_markers(self) -> None:
        self.write_runtime_notices(RUNTIME_NOTICES.replace(
            "2. Grant of Copyright License.", "2.\tGrant of\nCopyright License.",
        ))
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_stale_packaged_runtime_notices_with_complete_apache_text(self) -> None:
        path = self.root / "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md"
        path.write_text(RUNTIME_NOTICES + "\n", encoding="utf-8")
        self._git("add", path.relative_to(self.root).as_posix())
        self.assert_rejected("does not match")

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
            "THIRD_PARTY_NOTICES.md": RUNTIME_NOTICES,
            "app/src/main/assets/legal/LICENSE.txt": "GNU GENERAL PUBLIC LICENSE Version 3\n",
            "app/src/main/assets/legal/NOTICE.txt": "Open TimeManager\n",
            "app/src/main/assets/legal/ATTRIBUTION.md": "# Attribution\n",
            "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md": RUNTIME_NOTICES,
            "app/schemas/example/1.json": "{}\n",
        }
        for relative, contents in files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
        self._git("init", "-q")
        self._git("add", ".")

    def assert_paths_rejected(self, paths: tuple[str, ...], expected: str) -> None:
        for relative in paths:
            with self.subTest(path=relative):
                path = self.root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("synthetic private fixture\n", encoding="utf-8")
                self._git("add", "-f", relative)
                try:
                    result = self.run_checker()
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn(expected, result.stdout)
                    self.assertIn(relative, result.stdout)
                finally:
                    self._git("reset", "--", relative)
                    path.unlink()

    def check_ignored_paths(self, paths: tuple[str, ...]) -> subprocess.CompletedProcess[bytes]:
        (self.root / ".gitignore").write_bytes((ROOT / ".gitignore").read_bytes())
        return subprocess.run(
            ["git", "-C", str(self.root), "-c", f"core.excludesFile={self.root / '.git/info/exclude'}",
             "check-ignore", "--no-index", "--stdin", "-z"],
            input=("\0".join(paths) + "\0").encode("utf-8"),
            capture_output=True,
            check=False,
        )

    def write_runtime_notices(self, contents: str) -> None:
        for relative in ("THIRD_PARTY_NOTICES.md", "app/src/main/assets/legal/THIRD_PARTY_NOTICES.md"):
            (self.root / relative).write_text(contents, encoding="utf-8")
            self._git("add", relative)

    def write_resource(self, relative: str, contents: str) -> None:
        path = self.root / "app/src/main/res" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"<resources>{contents}</resources>\n", encoding="utf-8")
        self._git("add", path.relative_to(self.root).as_posix())

    def _git(self, *args: str) -> None:
        subprocess.run(
            ["git", "-C", str(self.root), *args],
            check=True,
            text=True,
            capture_output=True,
        )


if __name__ == "__main__":
    unittest.main()
