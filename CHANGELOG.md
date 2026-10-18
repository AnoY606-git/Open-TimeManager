# Changelog

All notable user-facing changes are recorded here. Preview versions have no stable compatibility promise before 1.0.

## [Unreleased]

## [1.1.0] - Upcoming

### Release status

- Planned stable update with `versionCode = 3`; see the [bilingual 1.1.0 notes](docs/releases/1.1.0.md). Final signing identity, data-preserving upgrade verification, CI results, source revision, checksums, and publication status belong to the release assets, not this changelog.

### Interface and guidance

- Settings now opens five focused screens: Appearance, Tracking and projects, Backups and restore, Help, and About.
- Added a searchable, fully offline English/Russian guide covering ten topics, including recovery, safe file handling, system controls, and background restrictions.
- Added a local-only default project. Active-session and explicit idle selections take priority; a valid preference enables widget/tile quick Start, otherwise quick Start still requires exactly one eligible project. The preference is not part of portable settings or backup schema.
- Expanded native Android controls: user-requested tile addition on Android 13+, manual fallback on Android 12, launcher-supported widget pinning with manual fallback, widget preview/cell sizing, app-details settings, and read-only Battery Saver/background-restriction status.
- Added advisory HyperOS/MIUI, One UI, and ColorOS/OxygenOS/realme UI guidance. No private Super Island/HyperIsland API, blanket battery exemption, or higher minimum SDK is claimed.
- Optional Android 16 Live Updates request promotion for a running timer using the standard platform API. Off by default, with per-session dismissal protection; Android and the OEM retain control of promotion. Uses the normal, non-runtime `POST_PROMOTED_NOTIFICATIONS` permission.
- History mutations retain the generation captured when a dialog opens, so stale edits/deletes cannot target replacement data. Editors pin their opening time zone; reports hide stale totals while a new range loads.
- Settings observers recover from transient I/O failures with capped backoff. Archive commands reject the active project transactionally; restore reports pending preference/privacy convergence after a committed database import.

### Backup and privacy

- Encrypted `.otmbackup` remains the default and retains `OTMB1` and payload schema v2 compatibility.
- Added explicit opt-in `.otmplain` (`OTMP1`) using the same strict payload schema and limits. It has no encryption or authenticity protection; SHA-256 detects accidental damage only. Separate creation/import warnings distinguish structural inspection from trust.
- Plaintext is not an automatic fallback after an encrypted-file or password error. Language and the local default project remain outside both portable backup payloads.
- Repository policy and ignore rules now cover `.otmplain` as private data. RU/EN checks aggregate all values XML files, reject duplicate text resource names, and compare format arguments without requiring translated theme files or nontranslatable constants.

### Fixed and reduced background work

- Transient preference-read I/O failures retry while observed instead of permanently completing the settings stream; strict mutation/export reads still fail closed.
- Project archiving rejects the reserved No project row and projects used by a live/review session at the database boundary.
- Paused/review notifications show fixed confirmed duration instead of an aging timestamp.
- Equal system projections no longer trigger repeated publications for unrelated Room invalidations; unchanged tile presentation is filtered, and static widget resizing no longer rereads Room or starts asynchronous broadcast work.
- Timing remains monotonic and event-driven, with no counting timer, wake lock, or alarm scheduler added. Battery impact has not been measured; no battery-life percentage or performance result is claimed.

## [1.0.0] - 2026-10-17

### Release

- First stable release; see the [bilingual release notes](docs/releases/1.0.0.md) for features and upgrade precautions. Exact signing, checksums, source revision, and verification results accompany the release assets.

### Design

- Redesigned all four screens with an ink-and-violet visual system, complete light/dark palettes, clearer typography, original line icons, and floating adaptive navigation.
- Reworked timer controls, project selection, history entries, report distributions, and grouped preferences without changing the tracking or backup formats.
- Refreshed the bilingual repository landing pages with a local brand mark, emulator galleries, dark-theme previews, and expandable technical documentation.

### Security and privacy

- New backup creation requires at least 12 Unicode code points and at most 1024 UTF-8 bytes without trimming or normalization; existing backups retain their original password compatibility.
- Full Apache License 2.0 text, including its Appendix, is available in the offline third-party notices.
- Repository checks reject private backup files, signing and environment credentials, internal planning directories, and accidentally tracked Android packages.
- Release APK checks validate merged manifest permissions, package/SDK metadata, non-debug status, ZIP integrity, and exact packaged legal documents.

### Build and contribution workflow

- CI pins the JDK and Android command-line tools revisions used by the local toolchain, and the app explicitly selects Build Tools 36.0.0.
- CI does not persist checkout credentials and preserves test/lint reports on failures; APK artifacts include SHA-256 checksums and the source commit.
- Added native Windows/PowerShell instructions and a release checklist covering signing, final checksums, and Corresponding Source.
- Added Russian and English screenshots captured from the Android 16 emulator with synthetic records, plus galleries in both READMEs.
- Public developer documentation is permitted while private plans remain excluded; text files use LF with CRLF for Windows batch launchers.
- Bash execution checks are explicitly POSIX-only, and the toolchain wrapper preserves standard Android and Gradle user-home settings unless explicitly overridden.

### Fixed

- User-initiated system timer commands use the foreground broadcast queue instead of waiting behind unrelated background broadcasts.
- Restore failures are visible inside the verified preview. Hide preserves it in process memory for retry after stopping a timer; explicit Cancel restore discards it. Busy restores reject duplicate actions and accidental dismissal.
- Merge can retry after initially detected conflicts are resolved locally; live conflicts are rechecked transactionally rather than permanently disabling the action from an old preview.
- CSV export reads one consistent, disk-spooled snapshot before opening a potentially slow document provider, without retaining the entire history in memory or holding the database transaction during external I/O.
- CSV filenames use the current or explicitly supplied range timezone instead of the process startup timezone.
- Notifications, widgets, tiles, CSV default-project labels, and project search honor the selected application language on Android 12/12L as well as newer Android versions.

## [0.1.0-preview] - 2026-10-15

### Added

- Local timer with start, pause, resume, stop, project switch, active note, and reboot review state.
- Project creation/archive, manual entries, editing, deletion, bounded history loading, and daily goal.
- Week, month, and custom reports up to 10 years with project totals and UTF-8 CSV export.
- Encrypted `OTMB1` backup schema v2 with complete project metadata and portable preferences, v1 compatibility, retryable verified preview, transactional Merge/Replace restore, UUID conflict detection, and 4 MiB mobile policy.
- Foreground notification, Quick Settings tile, and widget with privacy projection and stale-command checks.
- Adaptive Material 3 UI, dynamic color, light/dark/system themes, English/Russian localization, compact/rail/two-pane layouts.
- Searchable bounded project pages in the History entry editor, including safe editing of entries whose project is archived or outside the first page.
- Offline license and third-party notices in Settings.
- Android 12+ support (minimum API 31, target/compile API 36).

### Security and privacy

- No `INTERNET`, location, analytics, advertising, broad-storage, or account capability.
- Android automatic backup and device-transfer extraction are disabled.
- Portable backups use PBKDF2-HMAC-SHA256 and AES-256-GCM; CSV remains plaintext by design.
- Cancellation during restore rolls back every Room table; a durable convergence journal applies portable settings after the database commit without claiming cross-store atomicity.
- Manual portable-setting changes share the restore journal ordering boundary and retain a retryable full intent across DataStore or privacy-mirror failure, so an older restore cannot overwrite a newer user choice after restart.
- Partial settings changes use a strict DataStore read: a transient read error reports failure before any journal, DataStore, or privacy-mirror write instead of replacing unrelated settings with defaults.
- Backup creation drains the settings recovery journal and takes a strict, serialized Room/DataStore snapshot; a settings read or convergence failure aborts before a backup file is offered.
- A setting already committed to DataStore is reported as saved when its Android privacy mirror needs retry, with a localized warning and the durable recovery intent retained; mirror disk I/O runs off the main thread.
- Restore reconciliation and ordinary preference mutations share one process-wide serialization boundary; failed preference convergence keeps its durable retry intent.
- Active timers keep an exact checked duration summary for constant-cardinality system commands while retaining every original segment for reports, history, CSV, and backup. Commands write only the newly opened or closed segment.

### Known limitations

- Preview builds are not production-signed or published to Google Play.
- No cloud sync, teams, payroll, location automation, CSV import, or password recovery.
- Replace has no Undo after success; restore is blocked while a live/review timer exists.
- System controls depend on notification permission and device/OEM behavior.
- No independent cryptographic audit, Play policy approval, or verified performance/battery benchmark is claimed.
