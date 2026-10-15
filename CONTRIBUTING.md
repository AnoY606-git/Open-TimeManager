# Contributing

Thank you for improving Open TimeManager. Contributions should preserve its local-first, no-account, no-network-permission model unless a separately discussed product decision explicitly changes that boundary.

## Before opening a change

- Search existing issues and keep one change focused on one observable problem.
- For a bug, describe a reproducible user-visible scenario and expected behavior.
- For a feature, explain who benefits, what data or permissions it needs, and why the simpler current behavior is insufficient.
- Never attach real work logs, encrypted backups, passwords, signing material, or device identifiers.

## Quality gate

Use JDK 17 and Android SDK API 36, or the pinned Linux bootstrap from the README. Behavioral changes need a regression test that fails for the defect or missing capability and passes with the implementation. Before a pull request, run:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
python3 scripts/check_repository.py
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:compileDebugAndroidTestKotlin
```

With your own JDK/SDK, replace `scripts/with-toolchain.sh ./gradlew` with `./gradlew`.

Keep English fallback and Russian resources in parity. Do not add dynamic versions, mutable Action tags, broad permissions, production signing keys, generated toolchains, or private planning/research material. Room schema changes include an exported schema and non-destructive migration.

## Pull request

Explain behavior and risk, link the issue when one exists, list exact checks run, and identify anything not verified on an emulator or device. Compilation is not device execution. Contributions are accepted under GPL-3.0-only.

Report vulnerabilities privately according to [SECURITY.md](SECURITY.md), not through an issue or ordinary pull request.
