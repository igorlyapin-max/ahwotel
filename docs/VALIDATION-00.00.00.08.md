# Git delivery validation: 00.00.00.08

Date: 2026-10-21

## Delivery scope

- Baseline: `00.00.00.07`, tag `v00.00.00.07`, commit
  `0da1469b47182f1dc251fe54d791a39d9e31678f`.
- Release: `00.00.00.08`, Android `versionCode=26`, Room schema version `5`.
- Recovery push was not needed: local `main`, remote `main` and the peeled
  baseline tag matched before the release commit.
- Added the one-shot SOTI Exchange quarantine operator workflow, its fail-closed
  policy, approval and verification artifacts, diagnostics, tests and runbook.
- Added a rotating Collector file sink for SOTI audit logs.
- Android runtime behavior and the Room schema did not change. Android release
  metadata changed so installed APK versions can be identified unambiguously.

## Automated validation

```text
python3 -m unittest discover -s scripts -p 'test_*.py'
28 tests: OK

python3 -m py_compile scripts/soti_exchange_access.py scripts/test_soti_exchange_access.py
PASS

./scripts/gradle.sh :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline
BUILD SUCCESSFUL; lint: 0 errors, 26 warnings

docker compose config --quiet
PASS

otelcol-contrib validate --config=/etc/otelcol/config.yaml
PASS

git diff --check
PASS
```

`ruff` is not installed in the workspace, so no Ruff result is claimed.

## APK and connected-device verification

Artifact: `app/build/outputs/apk/debug/app-debug.apk`

- Size: 15,387,636 bytes.
- SHA-256: `220c15f2e605d779c65cf33342c152cee3e6bd9360ea7d72f36dcd5c2163bdb7`.
- Manifest: `versionName=00.00.00.08`, `versionCode=26`.
- APK Signature Scheme v2: verified.
- Debug certificate SHA-256:
  `93a8a0383bac8b686523bfe537985b459e61e8885ce00047b93f1dd7af6a3553`.

Every ADB-visible device in state `device` was updated with `adb install -r`:

| Serial | Model | Before | Installed | Launch | Fatal exception |
|---|---|---|---|---|---|
| `R9PT90TS6ZL` | `SM_T225` | `00.00.00.04` / code19 | `00.00.00.08` / code26 | `Status: ok`, PID `17430` | none in focused logcat |

No `offline` or `unauthorized` devices were present. The device was not rebooted.

## Runtime audit evidence

An isolated Collector used the committed configuration and a fresh temporary
volume. OTLP/HTTP accepted the log payload with HTTP 200 and created
`soti-exchange-audit.jsonl`. An event emitted through `EventLogger` stored a
hashed device identifier; the raw test identifier was absent. Temporary runtime
resources were removed afterward. See
[`VALIDATION-SOTI-EXCHANGE-ACCESS.md`](VALIDATION-SOTI-EXCHANGE-ACCESS.md).

## External and container gates

Live SOTI authentication and actions were not run because
`SOTI_CLIENT_ID`, `SOTI_USERNAME`, `SOTI_CLIENT_SECRET_FILE` and
`SOTI_PASSWORD_FILE` are not configured. Tenant-local Swagger must still be
checked and `actionContractVerified` must remain `false` until a single-device
pilot succeeds.

The repository has no CI/registry/provenance pipeline for the OTEL lab. The
locally available Collector image is therefore `unverified-local` and carries an
older revision label. The changed bind-mounted Collector configuration and its
real file-sink path passed validation and isolated runtime smoke, but this is not
a verified container release.
