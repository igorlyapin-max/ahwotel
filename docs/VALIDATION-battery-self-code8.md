# Validation: battery and Self Telemetry, code 8

Host date: 2026-09-13. Physical phone: Samsung SM-J260F, Android 8.1/API 27. Its own clock is in January 2018; the clock was not changed. Additional target: Android 15/API 35 x86_64 emulator without Knox.

## Delivered artifact

- APK: `artifacts/AHWOTel-00.00.00.01-code8-debug.apk`
- versionName: `00.00.00.01`; versionCode: `8`.
- SHA-256: `b808a41e3112f2d5a255f26534565a30110968ff19a4c7ff344ffac81b3849a0`.
- Signing certificate SHA-256: `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553` (same debug certificate as installed app).
- Installed into the main `com.ahwotel` package after the user explicitly approved stopping and resuming the active session. No Git commit/push in this task.

## Automated checks

28 focused JVM tests pass: AgentTelemetryTest 8, MigrationTest 2, TransportTest 6, SessionTimingTest 5, OemContractsTest 7. They cover stable/gapped/invalid discharge windows, bounded quantiles, wakelock timeout without double counting, configuration/debug expiry, independent outbox readiness, migrations retaining records and pending bytes, OTLP timestamps/identity, and HTTPS gzip retry of exactly the original bytes.

Final command:

```sh
./scripts/gradle.sh :app:assembleDebug :app:assembleAcceptance :app:assembleAcceptanceAndroidTest \
  :app:testDebugUnitTest --tests com.ahwotel.AgentTelemetryTest --tests com.ahwotel.TransportTest \
  --tests com.ahwotel.MigrationTest --tests com.ahwotel.SessionTimingTest --tests com.ahwotel.OemContractsTest \
  :app:lintDebug
python3 scripts/check-locales.py
```

Android Lint passes. Locale/help validation passes: 1261 EN/RU resources, including eight sections for each new metric. Final build output was `/tmp/ahwotel-code8-release-check.txt`; summaries are under `artifacts/agent-code8-unit-summary.json`.

## Device scenarios on the final APK

Three scenarios pass on each target:

1. Real APK worker collects battery and process metrics locally. Change battery frequency during a running session and switch to continuous; session ID and original start remain. Stop persists a partial window, and no new records appear afterward. CPU data and component cost are present; outbox stays empty with OTLP off.
2. Disable Self Telemetry while battery collection remains active. Verify battery data, Russian help, and the visible graph.
3. Enable debug telemetry while ordinary battery sampling is 60 seconds. Verify extra battery context at the fast interval and automatic return to normal sampling after expiry.

Evidence:

- `artifacts/agent-code8-phone-release.txt`: `OK (3 tests)`; 88.609 seconds.
- `artifacts/agent-code8-android15-release.txt`: `OK (3 tests)`; 79.177 seconds.
- `artifacts/agent-code8-battery-ru-release.png`: actual Russian graph, 0–100% axis, 5-minute time axis.
- `artifacts/agent-code8-self-en-release.png`: actual English CPU graph with labelled axes.
- Earlier candidate self snapshots and off/on experiment: `agent-code8-worker.json`, `agent-code8-worker-final.json`, `agent-code8-overhead.json`. These are intermediate-run evidence, not the final delivery hash.

Tests ran only in `com.ahwotel.acceptance`. Existing main data were not used as disposable test fixtures. No positive Knox wear capability is claimed: the tested Samsung has no verified accessible wear source for the ordinary APK.

## Background lifecycle regression

External ADB observation on Samsung used the final acceptance APK without instrumentation attached. Home, Back and removing the card from Recents each preserved the same process and continuous session, with at least 30 seconds of collection after exit. Samples increased from 2 to 19 for Home and Back, and from 2 to 22 for Recents. Reopening retained the session. Stop from the notification ended it with `manual_stop`, and no samples appeared afterward.

Evidence: `artifacts/agent-code8-background-home/result.json`, `artifacts/agent-code8-background-back/result.json` and `artifacts/agent-code8-background-recents/result.json`, with database snapshots, foreground-service/notification checks and screenshots in the adjacent folders. These tests targeted only the isolated acceptance package.

## Main app update and resumed operation

Original session `2a5d39c7-9052-4914-804d-bc9b14686b65` was stopped via the app button and confirmed `FINISHED / manual_stop`. Before upgrade: 31 sessions, 2844 samples, 0 queued packets, 2 OEM profiles, no OEM observation/inventory/event rows.

Migration to Room schema 4 preserved those counts exactly. The settings file was byte-for-byte identical before/after upgrade (SHA-256 `d3b0fd51e3fe532c5e68d5f3e6591206d46606a56b13f136895c5c0640ba289b`). Evidence: `agent-code8-upgrade-proof.json` and the stopped/upgraded archives.

Collection was resumed from the UI with the saved settings. New session: `a28b86c4-af88-4311-b003-754a8c20cddc`, `RUNNING`, continuous. At the recorded runtime checkpoint: 126 new base samples, 12 battery records, 72 Self Telemetry records, 5 wear/specification records, 0 queued packets. The original settings file remained identical. New feature defaults apply without rewriting the old settings file.

Actual main worker results: technology `Li-ion` is available; cycles/full/design capacity have no readable standard source; SOH has no verified source. Numeric values for unsupported wear metrics are null. No wear percentage is invented. See `agent-code8-runtime-proof.json`.

Installed package reports code 8, and PID 29993 started the new session. `agent-code8-main-logcat.txt` shows matching structured `agent_started` and `session_started` events through `System.out` and `AHWOTel` Logcat. No `FATAL EXCEPTION` or self-flush failure was observed in this log capture. Collection remains running as requested.

After all background regression checks, the same main session and PID were still running: 641 base samples, 240 battery records, 558 Self Telemetry records and 5 wear/specification records; outbox remained empty and settings identical. The temporary test screen setting `stay_on_while_plugged_in` was restored to `0`. The main app was opened for the user without stopping collection. Evidence: `artifacts/agent-code8-final-state.json`, `agent-code8-final-main.xml` and `agent-code8-final-main.png`.

## Limits of validation

The preliminary off/on CPU experiment used four 20-second steady windows after startup, with 1-second base collection and UI instrumentation. CPU fell in both modes as the process warmed up (off: 2090/1484 ms; on: 1551/1178 ms). This is inconclusive for incremental overhead; it does not establish the complete ≤5% objective. A longer, warmed, controlled benchmark including persistence and uploads is still required for that performance claim.

The seven existing SessionTimingAcceptanceTest scenarios also pass on the final APK (65.72 seconds): live timer switching, original-start deadlines, screen-off timing, storage-failure rollback, Stop/save race and prevention of late OEM records. Evidence: `agent-code8-timer-regression.txt`. Fleet analytics, a remote configuration backend, true per-app mAh and unavailable hardware counters are outside this APK increment as agreed.
