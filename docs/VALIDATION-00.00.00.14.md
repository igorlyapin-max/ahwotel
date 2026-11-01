# AHWOTel 00.00.00.14 / code31

## Scope

Performance-only OEM collection, removal of broad package visibility and Knox app-management permissions, independent lock-screen pause, schema 2 deployment profiles, release of trusted stored schema 1 management envelopes, saveable Settings drafts, and corrected EN/RU thermal-headroom methodology. Room remains at schema 5; historical tables and retired enum IDs are retained for safe reads, while retired data is excluded from new UI/exports/OTLP. Pending OEM packets are cleared once during the performance-scope migration.

## Focused checks

- `python3 scripts/check-locales.py`: PASS, 1543 matching EN/RU resources.
- `:app:testDebugUnitTest` filtered to ReviewCode31Test, CollectionPolicyTest, DeploymentProfileTest, SessionTimingTest, OemContractsTest, CpuSourcesTest and HistoryArchiveExporterTest: 44 tests, zero failures/errors.
- `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleAcceptance`, `:app:assembleAcceptanceAndroidTest`: PASS.
- `git diff --check`: PASS.
- `docker compose --env-file deploy/otel-lab/.env.example -f deploy/otel-lab/compose.yaml config --quiet`: PASS. No container source/config changes or container restarts were part of this release. The lab remains unverified-local; this is not CI/registry image-delivery evidence.

The deterministic regressions cover lock-before-commit skipping, delayed worker read cancellation, terminal session outcomes, managed lock configuration, trusted v1 envelope release/replay/corruption, and draft serialization without bypassing persisted-configuration validation.

## APK and runtime boundary

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, versionName `00.00.00.14`, versionCode `31`.

SHA-256: `ba05b2b652558249192d3abd8b208d37948bef10e228faaac91c9bbe73fc6d50`.

No ADB-visible devices were present. Installation, launch/FATAL EXCEPTION checks, physical lock/unlock behavior, Settings recreation and compact/large-text methodology UI acceptance remain **not verified**. Instrumentation APKs compile; compilation is not execution evidence. No device reboot was performed.

Existing Off/Basic/temporary Verbose structured stdout/stderr + Logcat/rotating JSONL diagnostics are preserved; live delivery was not revalidated without an Android runtime.
