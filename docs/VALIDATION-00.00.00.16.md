# AHWOTel 00.00.00.16 / code37

2026-11-02. Release baseline: VERSION `00.00.00.15`, annotated tag `v00.00.00.15`, local and remote peeled commit `730d01329c791486a4bf31475bd4fb976172c752`. Recovery push was not needed.

## Scope

ASPM task-affinity and local certificate-generator hardening, including the laboratory symlink entrypoint; atomic MDM identity updates; current-draft certificate-rule extraction; standalone rule connection preview; run-bound, complete-point mTLS delivery validation. HTTP remains an explicit administrator choice and Android 8+ remains supported. Portable schema 4 and Room schema 5 are unchanged.

Implementation and scenario evidence: [ASPM code35](VALIDATION-aspm4-code35.md), [five review fixes code36](VALIDATION-review-fixes-code36.md). The local response `../aspm/4-reply.csv` is outside this Git repository and is not part of this commit. ASPM rescan and real SOTI delegation/PKI acceptance remain pending; no scanner-server status was changed.

## Focused release validation

- Fresh code37 builds: assembleDebug, assembleAcceptance, assembleAcceptanceAndroidTest and lintDebug PASS.
- Fresh JVM tests: **31/31 PASS**, MtlsTest, CertificateRuleTest, HttpPolicyTest, DeploymentProfileTest, TransportTest.
- Fresh Python tests: **18/18 PASS**, test_mtls_lab.py, test_mtls_delivery.py, test_instrument.py, test_otel_lab.py.
- Locale validation: 1580 matching EN/RU resources PASS; diff whitespace check PASS. No complete-project suite was requested or run.
- Immediately preceding implementation acceptance on code36: **8/8 Android scenarios PASS**, including real KeyChain, standalone preview, managed upload, task boundaries and EN/RU. The new checker matched all six original Room RAM values/timestamps in Prometheus for that successful run. Code37 changes only release version metadata relative to code36; those eight scenarios and Collector ingestion were not repeated for this handoff.
- Off by default, Basic and temporary Verbose diagnostics and structured stdout/stderr, Logcat and rotating JSONL remain in place. No secret material or generated evidence is staged.

## APK and devices

Artifact `app/build/outputs/apk/debug/app-debug.apk`, applicationId `com.ahwotel`, versionName `00.00.00.16`, versionCode `37`.

SHA-256: `184c067cb1fe766a259b3eb3f8dcbbe9c0577715e55d7e80bbb6b12f4c43897d`.

The only ADB-visible device was emulator-5554. It was updated with install -r, installed version checked, launch returned Status: ok, process remained alive and its crash buffer contained no FATAL EXCEPTION. No physical, offline or unauthorized devices were listed. No reboot was performed. Local proof: `artifacts/release16/apk.json` (ignored).

## Delivery boundary

Git handoff delivers source and Android APK build metadata. The Collector/Prometheus/Grafana test lab stays on its existing ports and authentication None; the temporary mTLS Collector and ADB reverse were cleaned up after code36 acceptance. No container was restarted or image published for this handoff. The gkm-ci-container-delivery gate was evaluated: customer container-image delivery/provenance is not applicable to this APK release; local lab images remain unverified-local, with no claim of CI or registry verification. Custom-CA/key/hostname behavior has local JVM/Android test evidence, not a claim of customer PKI validation.
