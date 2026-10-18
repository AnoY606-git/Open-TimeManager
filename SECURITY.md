# Security policy

## Supported version

Security fixes target the latest published stable release and the default branch.
`1.1.0` is being prepared; until it is published, `1.0.0` remains the published
stable version. Earlier `0.1.x` preview builds are no longer supported. Create and
verify a separate encrypted backup before migrating from a differently signed
installation; never uninstall or clear data to bypass a signing mismatch.

## Report privately

Do not open a public issue containing an exploit, private records, backup files, passwords, device identifiers, or other sensitive material.

Use GitHub's **Private vulnerability reporting** for this repository: open **Security → Advisories → Report a vulnerability**. If that control is unavailable, contact the repository owner privately through their GitHub profile and ask for a private channel without sending exploit details publicly.

Include the affected revision/version, Android/API level, impact, minimal
reproduction, and whether it involves a crafted `.otmbackup` or `.otmplain`, stale
system/history action, local storage, or document provider. Use synthetic data
only. Neither plaintext nor encrypted personal backups belong in public issues,
Git, CI artifacts, or source archives; a plaintext checksum is not authenticity proof.

Acknowledgement uses the same private channel. Triage and disclosure timing depend on severity and fix availability; this community-maintained project promises no fixed response SLA. The security model and limitations are documented in [docs/SECURITY.md](docs/SECURITY.md).
