# AHWOTel 00.00.00.15 / code34

Checked on 2026-11-02. Baseline: `v00.00.00.14`, commit `a24d1b645680649ebaa165c2d37e7a1a8240eaac`.

## Scope

Optional OTLP/mTLS with Android KeyChain, system or app-scoped custom server CA trust, manual certificate selection and portable certificate rules. Rules support AD CS template OID, certificate policy OID, exact purpose SAN URI, issuer and OU; configured criteria are combined with AND. The individual device alias is supplied separately through managed restrictions. Optional self-grant requires actual Android certificate-selection delegation, an accepted managed profile and an explicit setting.

Portable deployment profiles now require schema 4. Re-export schema 1–3 profiles; old portable profiles are not accepted. Existing local settings restore manual selection. Room remains at schema 5. EN/RU configuration and certificate requirements are documented in [mtls.md](mtls.md) and [mtls-ru.md](mtls-ru.md).

## Release checks

- Debug, Acceptance and AcceptanceAndroidTest APK builds: PASS. `lintDebug`: PASS.
- 33 focused JVM tests, zero failures/errors: CertificateRuleTest, MtlsTest, DeploymentProfileTest, CollectionPolicyTest, TransportTest, HttpPolicyTest. The complete JVM suite was not run.
- Python: test_mtls_lab.py 2/2, test_otel_lab.py 6/6, test_instrument.py 2/2.
- Locale validation: 1579 matching EN/RU resources. `git diff --check`: PASS.
- Default and mTLS Compose configurations validate with committed example environment files.
- API 35 emulator: final instrumentation run **6/6 PASS**, including actual system PKCS#12 enrollment/KeyChain selection, managed rule/binding, read-only management, EN/RU UI, local collection and real OTLP/mTLS transmission. Initial runs failed because the newly installed test alias was outside the visible KeyChain list; the test now scrolls to that alias. No application behavior was changed for this test repair.
- Fresh end-to-end evidence: deleted the old test result before the run, checked the new result against the run start, read the current test session from Room and matched **all 6 original values and timestamps** in Prometheus. This release check uses a separate temporary verifier; it does not rely on the known stale-result limitation of `scripts/check-mtls-android.py`.

Local evidence, excluded from Git: `artifacts/release15/android.txt`, `fresh-delivery.json`, `apk.json`; JVM XML and lint reports are under `app/build/`.

## APK and device proof

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, applicationId `com.ahwotel`, versionName `00.00.00.15`, versionCode `34`.

SHA-256: `894a77fa4362fe04f4a7a26dfb76921f5a8185d60711d61e958bb19ffd7a6308`.

The only ADB-visible device was `emulator-5554`: updated with `adb install -r`, installed version checked, activity launch returned `Status: ok`, process remained alive and its crash buffer contained no `FATAL EXCEPTION`. No offline/unauthorized devices were listed. Samsung was absent from ADB, so physical-device installation and acceptance are not verified. No device was rebooted.

Off by default, Basic and temporary Verbose diagnostics and structured stdout/stderr, Logcat and rotating JSONL routes remain in place. New diagnostics use bounded codes rather than certificate aliases, Subject/SAN, private keys or passwords.

## Runtime and delivery boundary

The existing Collector/Prometheus/Grafana lab remains running with authentication None. Android mTLS acceptance used an isolated loopback Collector with certificates generated for the current test APK and ADB reverse. That temporary Collector was stopped and its reverse removed after validation; the main listener was not switched to mTLS for this release check.

The delivered product is an Android APK. The laboratory containers remain **unverified-local**; Compose validation and live telemetry checks do not establish customer image provenance, CI execution or registry publication. No new container image was published as part of this Git handoff.

## Open review findings and acceptance limits

Four P2 findings from the preceding review remain open in this release:

1. `ManagedConfiguration.replace`: binding cancellation precedes binding persistence without the export gate. A concurrent new send can capture the new cancellation epoch with the old alias.
2. `TlsRuleEditor`: asynchronous reference-certificate extraction can apply an old captured draft over newer edits, including the delegation flag.
3. Standalone rule preview: Check rule can succeed with a reference alias, but Check connection requires a managed binding and cannot validate that standalone rule configuration end to end.
4. `scripts/check-mtls-android.py`: no run freshness check and only the first point is required, so old or incomplete results can pass. The separate release check above avoids this limitation without fixing the shipped script.

Real SOTI MobiControl **2026.2.2.1001**, Device Owner macro substitution, certificate-selection delegation/self-grant, impact on other VPN/SSO clients and a two-physical-device deployment pilot remain unverified. The emulator key was granted by system selection; this is not proof of real SOTI delegation. Mass automatic deployment is not accepted until that pilot is completed.
