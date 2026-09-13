# Review remediation validation: code 10

Scope: six findings from the latest review of the working tree relative to Git HEAD `5a8710d6aac6c35bcd7a006c1947a0b2b62af6fd`. Earlier uncommitted OEM, timer and battery/Self changes are retained. Version name remains `00.00.00.01`; versionCode is 10. No commit or push is part of this task.

## Corrected contracts

| Finding | Result | Evidence |
| --- | --- | --- |
| One WorkManager task per `upload-$at` deadline | A fixed unique chain permits one active uploader and at most one successor; each worker makes at most 100 attempts within a 20-second loop budget, excluding an in-flight request timeout. The persisted outbox owns eligibility and retry deadlines. Earlier packets advance a waiting job; the successor is persisted before the worker completes. | Native `UploadAcceptanceTest`: 1000 packets, 100 HTTP 503 responses, at least 1100 requests, concurrent scheduling and no more than two unfinished jobs; future packet not sent early, newly due packet delivered; recovery, endpoint change and disable checks |
| CSV/selected export scope lost across Activity recreation | `PendingExport` saves format, telemetry kind, session and time bounds with `rememberSaveable`; a missing request fails rather than defaulting to a broader export. | JVM codec test; three native CSV tests use actual Activity recreation, wait for visible success, then inspect real output. OEM fixtures outside the selected period/session are excluded. |
| Battery/Self-only profiles depend on hidden legacy `BATTERY` | Start validates the effective ordinary, battery, Self and OEM selections. Empty ordinary metrics are valid when another collector is enabled. A fully disabled profile can be saved, but Start returns a localized `no_collectors` error. | JVM and native matrices: both legacy flag values × Battery-only/Self-only; UI save/Start and stored telemetry; malformed metrics remain rejected |
| First WakeLock acquisition lost on initial tick | Coordinator state is initialized at session start, so the initial tick no longer discards acquisition costs. | Real coordinator test through final flush; native Self-only profile records one acquisition |
| WakeLock maximum leaks across sessions/tracking boundaries | Session start clears observed hold state and maximum. Disabling tracking excludes that interval; enabling it during a physical hold records only subsequent observation time and no invented acquisition. | JVM session/tracking boundary test; existing MAX aggregation/final-flush regression |
| English prose in Russian metric help | Russian explanatory prose is translated; genuine API and wire identifiers remain intact. EN/RU WakeLock help explains observation boundaries. | Generator, locale validator and native Russian help scenario |

The queue uses WorkManager's public `updateWork` API, which preserves enqueue time; execution remains subject to dependencies and Android scheduling. See the [official updateWork documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/update-work). Startup cancels the previous worker-class tag before reconciling the outbox. This is the targeted upgrade path; no broader legacy layer is added. Room remains version 4, and historical observations are not recalculated.

## Local validation

- 41 focused JVM tests passed, zero failures/errors: ReviewCode10Test, CoordinatorRegressionTest, AgentTelemetryTest, TransportTest, SessionTimingTest, CoreTest and CommandsTest.
- `:app:assembleDebug`, `:app:assembleAcceptance`, `:app:assembleAcceptanceAndroidTest` and `:app:lintDebug` passed.
- `python3 scripts/check-locales.py`: 1288 EN/RU strings, matching placeholders and help contracts passed.
- `python3 scripts/test_instrument.py`: two isolation-guard tests passed. Both APK manifests are checked before installing only `com.ahwotel.acceptance` and its test package.
- `git diff --check`: passed.

APK: [AHWOTel-00.00.00.01-code10-debug.apk](../artifacts/AHWOTel-00.00.00.01-code10-debug.apk).

SHA-256: `eb4402b72e9e060f226cd7f17c8c97ec97cf09b3234636b23ce4f6cd1f7a8c99`.

Signing certificate SHA-256: `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`, unchanged from code 9. Build summary: `artifacts/code10-build-summary.json`.

## Native validation on the API 35 emulator

Twenty-one distinct scenarios passed across the following runs:

- `artifacts/code10-final-emulator.txt`: 19 passed and two export test-helper failures. Passed contracts include the seven timer regressions, Battery/Self worker behavior, Back navigation, OEM scheduling, screen-off pause accounting, EN/RU chart labels and diagnostic sinks.
- `artifacts/code10-focused-emulator-r5.txt`: the final extended uploader test and profile matrix passed; two export UI synchronization/scroll assertions failed.
- `artifacts/code10-export-emulator-r6.txt`: all three export scenarios passed after fixing the helper to await completed writing and scroll to the status text itself.

This combines unchanged runtime scenarios with their focused final checks; it is not a claim of one wholly green 21-test run. Earlier failed reports are retained as diagnostic history. Machine-readable final per-scenario evidence is in `artifacts/code10-native-summary.json`.

The export fixture uses a controlled ActivityResultRegistry instead of the system DocumentsUI. It recreates the actual screen Activity while a document request is pending, delivers its result, and verifies visible completion and real CSV files. It does not prove restoration after OS process death while DocumentsUI is open. The queue recovery scenario creates a fresh scheduler over persisted WorkManager/outbox state and cancels a legacy-tag fixture; it does not simulate device reboot or process death during an HTTP request.

The HTTPS uploader test uses an in-process mock server and a test-only trusted certificate; it proves local queue delivery behavior, not delivery to a real external collector. Production TLS settings are unchanged. The diagnostic scenario verifies Basic/temporary Verbose, expiration and delivery to JSONL, AHWOTel Logcat, System.out and System.err. The brief overhead experiment is comparative evidence only; it does not establish a 5% overhead bound.

## Samsung delivery: pending

Samsung SM-J260F (Android 8.1/API 27) appeared briefly, then disconnected during the isolated native run. `artifacts/code10-final-samsung.txt` is incomplete and contains failed assertions; it is not passing Samsung acceptance evidence. On the last connection check, ADB listed only the emulator.

The main `com.ahwotel` APK has **not** been updated to code 10. Its last observed version was code 9, with continuous session `30dc65dd-36b9-4e57-9e32-3fee48417d54` and PID `7143`. The main session was not stopped by these tests. Its current state cannot be verified while the device is disconnected.

Remaining authorized work after reconnection: repeat the focused Samsung scenarios, verify Home/Back/Recents collection externally, stop the main session normally, install the signed APK with data preserved, and resume a new continuous session. Verify unchanged settings, installation ID, retained history/queue, notification and newly persisted base/Battery/Self data. Do not change the phone's existing wall clock. Delivery to the physical device remains incomplete until those checks pass.
