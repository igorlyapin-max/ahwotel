# Review remediation validation: code 9

Scope: the eleven findings from the battery/Self Telemetry review relative to Git HEAD `5a8710d6aac6c35bcd7a006c1947a0b2b62af6fd`. This increment retains the earlier uncommitted OEM, timer and battery/Self work. Version name remains `00.00.00.01`; versionCode is 9. No commit or push is part of this task.

## Corrected contracts and regression evidence

| Finding | Result | Verification |
| --- | --- | --- |
| Short existing OTLP queue age blocked settings initialization | Queue age 1–23 hours remains valid independently of upload interval | `AgentTelemetryTest.oldShortQueueSettingsLoadWithoutChangingUserValues`, OTLP and battery on/off matrix |
| Daily wear packet expired before its first delivery time | Expiry is `max(createdAt + retentionMs, dueAt + 3600000)`; retries do not extend it; byte quota still applies | Room-backed expiry boundary test and `CoordinatorRegressionTest.byteQuotaStillRemovesOldestFuturePacket` |
| OEM upload interval did not reach queued packets | New OEM packets use the effective local/managed interval; zero means immediately eligible | Native `localAndManagedUploadIntervalsReachOemOutbox`, local/managed × 0/3600 seconds |
| WAKE_MAX used a mean | Aggregation uses MAX, including final partial-window flush | `CoordinatorRegressionTest.realCoordinatorPersistsMaximumAcrossWindowAndFinalFlush` |
| Screen-off pause inflated scheduler delay/missed work | Resume resets the next expected sampling deadline | Native `screenOffPauseDoesNotBecomeSchedulerDelay` |
| Battery counter reversal was not invalidated | Independent charge/percentage baselines; per-sample rises or missing values restart only the affected counter | `AgentTelemetryTest.counterReversalBelowBaselineRestartsOnlyChargeWindow` |
| Configuration hash described next-session defaults | Revision 2 combines START collector settings with effective live settings | `AgentTelemetryTest.effectiveConfigurationTracksStartSnapshotAndLiveSettings` |
| Charts truncated history at 1000 rows | Stable ID snapshot, time/ID pagination and at most 240 buckets per component | `AgentChartTest`: 2882 rows, both ends and all counts retained |
| SUM/MAX/state legends used gauge semantics | Weighted GAUGE, explicit SUM/MAX, localized nominal labels and mixed/gap markers | `AgentChartTest` plus native `sumsMaximaAndNominalStatesHaveAccurateLocalizedPlotLabels`, EN/RU |
| System Back left child screens incorrectly | Dialog/menu dismissal precedes returning to Monitor/History | Native `systemBackClosesHelpThenReturnsToBothParentsInEnglishAndRussian`, both parents and both child screens |
| Help omitted wire identifier prefix | Generated help includes full `agent.*` / `device.*` identifier | `scripts/check-locales.py` |

UI validation also exposed loss of epoch-millisecond precision in tap selection: Float arithmetic could choose a point about a minute away from the tapped time. Selection now uses Double; a JVM regression covers historical/current dates and the nearest-point result. Nominal Y axes now contain only observed codes; absent fractional states are not fabricated. It also exposed a stale query-result race when switching the selected metric/range. The chart now displays only a result matching the current request and uses a stable time anchor for empty history. No existing history values or payloads are rewritten; Room remains version 4.

## Local checks

- 44 focused JVM tests passed, zero failures/errors: AgentTelemetryTest, AgentChartTest, CoordinatorRegressionTest, TransportTest, MigrationTest, SessionTimingTest, OemContractsTest, OemWorkerTest and ChartScaleTest.
- `python3 scripts/check-locales.py`: 1287 EN/RU resources, placeholders and metric-help contracts passed.
- `python3 scripts/test_instrument.py`: two isolation-guard tests passed. The harness validates both APK manifests before installation and targets only `com.ahwotel.acceptance`.
- `:app:assembleDebug :app:assembleAcceptance :app:assembleAcceptanceAndroidTest :app:lintDebug --offline`: passed on the final product sources.
- APK: `artifacts/AHWOTel-00.00.00.01-code9-debug.apk`, SHA-256 `9c9e09c5ad5dfcd3dd8dda0431a433043d88876f5281129b1446d717f817c5d3`. Signing certificate SHA-256 remains `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`.

An early Lint run encountered an internal Kotlin FIR analysis exception; a clean `:app:lintDebug --offline --rerun-tasks` passed. Early native reports retain failed attempts: a stale test APK, test scroll/window timing issues, an emulator System UI ANR overlay, and the query-result race. They are diagnostic history, not delivery evidence.

## Device and runtime validation

Fifteen distinct native scenarios passed on each target. Reports `artifacts/code9-final-samsung-r2.txt` and `artifacts/code9-final-emulator-r2.txt` contain fourteen passing scenarios each and the pre-fix chart selection failure. The chart scenario was then repeated on the final APK and passed: `artifacts/code9-complete-charts-samsung.txt` (32.18 s) and `artifacts/code9-complete-charts-emulator.txt` (16.168 s). This combines the unchanged runtime contracts with the focused final chart check; it is not a claim that either earlier fifteen-test run was wholly green. Machine-readable summary: `artifacts/code9-native-summary.json`.

The passed scenarios include all seven timer regressions, three battery/Self worker scenarios, Back navigation, OEM scheduling, pause accounting and the diagnostic pipeline. Basic/temporary Verbose behavior and delivery to JSONL, AHWOTel Logcat, System.out and System.err were verified by `diagnosticsUseFileAndSystemSinksAndVerboseExpires`.

Test targets: Samsung SM-J260F Android 8.1/API 27 and AOSP emulator Android 15/API 35. The Samsung wall clock remains at its existing January 2018 value; it is not adjusted by these checks.

No real collector or licensed Knox environment is asserted. The native OEM queue timing test uses a closed device-loopback HTTPS port; it proves settings reach the outbox, not remote delivery. Hardware-unavailable wear/Knox counters remain unsupported. No new 5% overhead claim is made.

Final EN/RU chart screenshots: `artifacts/code9-{samsung,emulator}-{sum,state}-{en,ru}.png`. Visual inspection confirmed visible axes, time-zone labels, selected sums and discrete nominal codes with localized explanations.

## Background collection on Samsung

The final acceptance APK was observed externally using `scripts/check-background.py`; no instrumentation was attached during these checks. Configuration was saved through the test app UI: continuous, 2-second sampling, screen-off collection enabled, Basic, OTLP off.

| Exit action | Base samples before/after | Same PID/session | Notification Stop and no later samples |
| --- | --- | --- | --- |
| home | 2 → 19 | passed | passed |
| back | 2 → 19 | passed | passed |
| recents | 2 → 22 | passed | passed |

Each scenario observed at least 30 seconds of continued sampling, verified the foreground service and notification, and reopened the same session. Evidence: `artifacts/code9-background-{home,back,recents}/result.json` and adjacent snapshots/screenshots. The original main process remained PID 29993 during these checks.

## Main application update and resumed collection

The user-authorized update was completed on `com.ahwotel`, after the isolated tests. The previous continuous session `a28b86c4-af88-4311-b003-754a8c20cddc` was stopped through its UI and ended with `manual_stop`. Before installation, the database held 32 sessions, 5253 base samples, 4007 telemetry records and an empty outbox. The signed code 9 APK was installed with `adb install -r`.

A first post-install file copy failed one index consistency check. A fresh copy taken with the application process stopped passed `PRAGMA integrity_check` and matched every table row, settings bytes and installation ID from the stopped baseline. No device database repair or restore was performed. The inconsistent copy is retained as diagnostic evidence; the verified install snapshot is `artifacts/code9-main-installed-r2`.

New continuous session: `30dc65dd-36b9-4e57-9e32-3fee48417d54`, PID `7143`. At the verification checkpoint it had 134 new base samples, 12 battery records and 64 Self Telemetry records, all new telemetry carrying `agent.build=9`. The foreground service and its notification were present; the running main UI was reopened. Existing settings remain byte-identical (SHA-256 `d3b0fd51e3fe532c5e68d5f3e6591206d46606a56b13f136895c5c0640ba289b`), including Russian language, continuous mode and screen-off collection. Installation ID, old history and empty queue are preserved; Room remains version 4.

Startup legitimately normalized SQLite's locale metadata from `ru` to `ru_RU` and refreshed the two OEM profiles' check timestamps. Provider capabilities/statuses and every historical row remained unchanged. The persisted original settings have no new battery/Self fields; the existing decoded defaults were preserved without rewriting that file. Battery/Self persistence is windowed, so the check waited for stored telemetry instead of requiring an immediate row after Start.

Evidence: `artifacts/code9-upgrade/result.json`, `install.txt`, `services.txt`, `notification.txt`, `resumed.png`, `last-ui.xml` and the database checkpoint `artifacts/code9-main-final2-0`. `stay_on_while_plugged_in` was restored to `0`; the test emulator was shut down. Collection remains running in the main application. No Git changes were staged, committed or pushed.
