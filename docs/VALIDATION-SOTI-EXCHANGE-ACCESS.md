# Validation: SOTI Exchange access operator tool

Date: 2026-10-21

## Scope

The validation covers the one-shot SOTI operator script, its fail-closed policy,
approval workflow, diagnostics and OTLP audit delivery. No Android runtime
behavior or Room schema was changed; the Git handoff only updates Android
release metadata. Live SOTI calls were not attempted because this environment has no
`SOTI_CLIENT_ID`, `SOTI_USERNAME`, `SOTI_CLIENT_SECRET_FILE` or
`SOTI_PASSWORD_FILE` configuration.

## Automated checks

```text
python3 scripts/test_soti_exchange_access.py
14 tests: OK

python3 -m unittest discover -s scripts -p 'test_*.py'
34 tests: OK

python3 -m py_compile scripts/soti_exchange_access.py scripts/test_soti_exchange_access.py
PASS

otelcol-contrib validate --config=/etc/otelcol/config.yaml
PASS
```

Focused tests cover corporate group boundaries, management/enrollment state,
quarantine and allowed states, multiple Exchange records, partial/paginated
device collections, snapshot and policy binding, expiry, idempotency, live
recheck before mutation, ambiguous POST handling without retry, secret file
permissions, log redaction, mandatory external audit preflight and Collector log
pipeline configuration.

`ruff` is not installed in the workspace, so no Ruff result is claimed.

## Runtime audit scenario

A temporary isolated Collector used the committed `collector.yaml` and a fresh
temporary Docker volume. An OTLP/HTTP JSON log sent to `/v1/logs` returned HTTP
200 with empty `partialSuccess`. The file exporter created
`soti-exchange-audit.jsonl`; its content included the requested audit event.

The same path was then exercised through `EventLogger`. The stored event showed
`deviceId=sha256:44318e99c238`; the raw test identifier was absent. The temporary
container and volume were removed after verification. The regular lab was not
started or modified.

## Remaining live gate

Before the first `apply`, an administrator must inspect tenant-local Swagger,
populate the exact response fields/status values and action payload, create the
dedicated API client and SOTI service user, then run `auth-check`, `inspect` and a
single-device pilot. Until that gate passes, keep
`actionContractVerified=false`.
