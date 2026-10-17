# Changelog

All notable user-facing changes are recorded here. Preview versions have no stable compatibility promise before 1.0.

## [Unreleased]

### Security and privacy

- New backup creation requires at least 12 Unicode code points and at most 1024 UTF-8 bytes without trimming or normalization; existing backups retain their original password compatibility.
- Full Apache License 2.0 text, including its Appendix, is available in the offline third-party notices.
- Repository checks reject private backup files, signing and environment credentials, internal planning directories, and accidentally tracked Android packages.
- Release APK checks validate merged manifest permissions, package/SDK metadata, non-debug status, ZIP integrity, and exact packaged legal documents.

### Build and contribution workflow

- CI pins the JDK and Android command-line tools revisions used by the local toolchain, and the app explicitly selects Build Tools 36.0.0.
- CI does not persist checkout credentials and preserves test/lint reports on failures; APK artifacts include SHA-256 checksums and the source commit.
- Added native Windows/PowerShell instructions and a release checklist covering signing, final checksums, and Corresponding Source.
- Public developer documentation is permitted while private plans remain excluded; text files use LF with CRLF for Windows batch launchers.
- Bash execution checks are explicitly POSIX-only, and the toolchain wrapper preserves standard Android and Gradle user-home settings unless explicitly overridden.

### Fixed

- Restore failures are visible inside the verified preview. Hide preserves it in process memory for retry after stopping a timer; explicit Cancel restore discards it. Busy restores reject duplicate actions and accidental dismissal.
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
