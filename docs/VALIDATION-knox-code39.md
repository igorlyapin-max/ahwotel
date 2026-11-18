# Knox SDK integration — code39

2026-11-18. APK `com.ahwotel`, versionName `00.00.00.16`, versionCode 39. Git handoff was not requested. Main artifact: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `308b02c4f160b2bd0a0c70ffb0cf8816892b2815e177493719399501f0f26b78`.

## Implemented

- Knox SDK 3.13/API 40 compileOnly stub staged by Gradle from the pinned local archive. All five supplied SDK archives were inspected for four DeviceInventory capacity methods. [Matrix](knox-api-matrix.md) records versions, hashes, method scope/deprecation and unsupported counters.
- Local SAF key import, private AES-GCM/Android Keystore storage, explicit activation, API 35+ callback and older broadcast-triggered authoritative record checks. Broadcast extras cannot authorize success/grants. Keys never enter Settings, portable profile, history, OTLP or application logs. A real development key was subsequently imported and activated on the connected Samsung, as described below.
- Separate MDM restrictions `knox_license_key` / `knox_license_action=activate`; same-key/action delivery is idempotent across restarts. To rearm remove the action and apply, then deliver activate again. No automatic deactivation or Device Owner transition.
- EN/RU Settings panel with availability, key presence, last activation result, safe code, callback permission count and instructions. A recorded activation is not proof of current license validity; callback permissions and actual getter access remain separate.
- Four capacity getters on the existing bounded OEM worker, daily Device cadence plus initial session observation. OEM/Android sources stay separate. -1, missing methods and denied access never become numeric zero. Existing folded unavailable groups and localized metric help apply to the new entries.
- Customer instructions: [Russian](knox-customer-guide-ru.md) / [English](knox-customer-guide-en.md). Registration, APK association/signing, development/commercial/BCK boundaries, local and SOTI configuration, renewal, diagnostics, SDK build preparation and network prerequisites are covered.

## Verified locally

- Debug, Acceptance and AcceptanceAndroidTest builds and lintDebug PASS. The first sandbox build was blocked by Gradle socket discovery; rerunning outside sandbox succeeded. No runtime network/TLS policy was weakened.
- **22 focused JVM tests PASS**: KnoxStorageTest 2, OemContractsTest 7, OemWorkerTest 2, CollectionPolicyTest 4, DeploymentProfileTest 7. One old assertion expected an empty Knox registry; updated to assert exactly the four approved capacity getters while continuing to forbid retired inventory/security/policy calls.
- **4 native scenarios PASS** on API 35 emulator: synthetic-key encryption/exclusion from portable export, real application-restrictions parsing, duplicate/rearmed managed activation with an injected backend (callback from another thread), EN/RU UI without Samsung, a storage fixture through Room/EN-RU History ZIP/OTLP serialization, and managed read-only lifecycle. The system activation call runs outside the state monitor to avoid blocking callbacks. Test backend calls are not real Samsung licensing evidence.
- Locale validation PASS: 1636 matching EN/RU strings, 64 OEM metrics with all eight help sections. `git diff --check` PASS.
- APK DEX class definitions inspected: no `com.samsung.android.knox` SDK classes packaged, no SDK archives/JAR or key files included. Build-only dependencies remain ignored. Source `.tools/knox/` and `secrets/` are ignored and contain no Git-tracked files.
- Bounded `knox_activation_result` events observed in structured stderr and Logcat. Existing Off/Basic/temporary Verbose and JSONL logging remain intact; no raw key or licensing payload is logged.
- All ADB-visible devices updated with install -r: Samsung SM-T225 and emulator-5554. Installed version/code, successful launch, live process and absence of FATAL EXCEPTION in its crash buffer verified. No physical device was rebooted; the existing test AVD was started for this validation.

Evidence (ignored): original code38 `artifacts/knox-code38/android-final.txt`; final code39 `artifacts/knox-code39/android.txt`, its run manifest, `apk.json`, `physical.json`, `tablet-capacity.png`; JVM XML/lint reports in `app/build/`. Final code39 builds/lint, six KnoxStorage/CollectionPolicy JVM tests and all four native scenarios passed again. No full-project suite was requested or run; no Collector or external OTLP ingestion was exercised in this task, only the serializer boundary for the new fixture.

## Physical Samsung acceptance

SM-T225, Android 14 (SDK 34), Knox API 37/platform 3.10. Local SAF import into Android Keystore-backed encrypted storage succeeded. The temporary shared Download copy was removed immediately after import. Explicit activation returned a successful SDK callback, code **0**, with zero additional granted permissions. No new consent dialog appeared; no consent was accepted automatically.

After activation, checks confirmed encrypted ciphertext/IV in private preferences with no raw key, no raw key in this process's Logcat buffer, removal of the temporary import file, and no FATAL EXCEPTION. These checks output only booleans; the license contents are not evidence artifacts.

The main APK's bounded OEM worker measured `internal_total = 23783784448 By` and `internal_available = 13421592576 By` in the observed snapshot. Both have AVAILABLE/NONE and device/OEM provenance. External total/available returned UNAVAILABLE/MISSING_VALUE, never fabricated zero. These storage readings were already available before activation; the licensing success does not establish that the license was necessary for these getters or unlock CPU/thermal APIs. The continuous session resumed under the existing settings; collection settings were not edited for this check.

A separate startup defect was discovered on this tablet: WorkManager's database held about 108k completed upload work records and was pruning them on open. A thread stack showed `UploadScheduler.recover()` blocking application readiness while WorkManager was in SQLite CleanupCallback. Code39 publishes application readiness before starting upload recovery in a separate supervised coroutine; Settings and OEM cards are now visibly usable during recovery. The work graph/outbox was not deleted. This change removes the UI startup dependency; it does not claim to repair historical work-graph growth or accelerate WorkManager's internal pruning. No tablet reboot, app-data reset or manual queue deletion was performed.

Still **NOT VERIFIED**: old-Knox broadcast behavior, a fresh Samsung consent workflow, production/commercial key association/renewal, SOTI 2026.2.2.1001 secret/action delivery and its device-role grants. The supplied development key was used only for the authorized test; its contents are absent from this report and evidence.

The supported scope does not include other applications' CPU/RAM, package inventory, broad KNOX_APP_MGMT/QUERY_ALL_PACKAGES, system CPU/thermal/wear APIs without an official contract, BCK activation, or a grant of SOTI's Device Owner privileges to AHWOTel. System CPU/thermal placeholders retain UNSUPPORTED/NO_OFFICIAL_API; standard Android collectors continue independently. Storage APIs are deprecated from Knox API 35, so additional readings are never promised for every model/OS.
