# Changelog

All notable user-facing changes are recorded here. Preview versions have no stable compatibility promise before 1.0.

## [0.1.0-preview] - 2026-10-15

### Added

- Local timer with start, pause, resume, stop, project switch, active note, and reboot review state.
- Project creation/archive, manual entries, editing, deletion, history search, and daily goal.
- Week, month, and custom reports with project totals and UTF-8 CSV export.
- Encrypted `OTMB1` backup, verified preview, Merge/Replace restore, UUID conflict detection, and 4 MiB mobile policy.
- Foreground notification, Quick Settings tile, and widget with privacy projection and stale-command checks.
- Adaptive Material 3 UI, dynamic color, light/dark/system themes, English/Russian localization, compact/rail/two-pane layouts.
- Offline license and third-party notices in Settings.
- Android 12+ support (minimum API 31, target/compile API 36).

### Security and privacy

- No `INTERNET`, location, analytics, advertising, broad-storage, or account capability.
- Android automatic backup and device-transfer extraction are disabled.
- Portable backups use PBKDF2-HMAC-SHA256 and AES-256-GCM; CSV remains plaintext by design.

### Known limitations

- Preview builds are not production-signed or published to Google Play.
- No cloud sync, teams, payroll, location automation, CSV import, or password recovery.
- Replace has no Undo after success; restore is blocked while a live/review timer exists.
- System controls depend on notification permission and device/OEM behavior.
- No independent cryptographic audit, Play policy approval, or verified performance/battery benchmark is claimed.
