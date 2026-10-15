# Changelog

All notable user-facing changes are recorded here. Preview versions have no stable compatibility promise before 1.0.

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
- Restore reconciliation and ordinary preference mutations share one process-wide serialization boundary; failed preference convergence keeps its durable retry intent.
- Active timers keep an exact checked duration summary for constant-cardinality system commands while retaining every original segment for reports, history, CSV, and backup. Commands write only the newly opened or closed segment.

### Known limitations

- Preview builds are not production-signed or published to Google Play.
- No cloud sync, teams, payroll, location automation, CSV import, or password recovery.
- Replace has no Undo after success; restore is blocked while a live/review timer exists.
- System controls depend on notification permission and device/OEM behavior.
- No independent cryptographic audit, Play policy approval, or verified performance/battery benchmark is claimed.
