# SOTI Exchange access operator tool

`scripts/soti_exchange_access.py` is a one-shot administrative tool. It runs on
an operator workstation or automation VM that can reach the SOTI MobiControl API
and the OTLP audit endpoint. It does not run on Android, does not use ADB or Knox,
and does not connect directly to Exchange.

## SOTI authorization

For the SOTI MobiControl 2024/2025 Password grant, create two identities:

1. An API client in **Global Settings → Services → API Client**. Save its client
   ID and secret when they are generated.
2. A dedicated local SOTI service user. Limit it to the corporate device groups
   in scope. It needs read access to those devices and `Manage Device Exchange
   Access` for `AllowExchangeAccess`.

The API client creator needs `Manage API Clients`; the runtime service user does
not. Confirm the actual grant and request contracts in the tenant-local Swagger
at `https://<server>/MobiControl/api`. The tool supports the Password grant with
HTTP Basic API-client credentials and service-user credentials in the token
request. It deliberately fails on a different grant instead of guessing.

Secrets are never CLI arguments or JSON configuration values:

```bash
export SOTI_CLIENT_ID='generated-client-id'
export SOTI_USERNAME='dedicated-local-service-user'
export SOTI_CLIENT_SECRET_FILE='/secure/soti-client-secret'
export SOTI_PASSWORD_FILE='/secure/soti-user-password'
chmod 600 "$SOTI_CLIENT_SECRET_FILE" "$SOTI_PASSWORD_FILE"
```

The tool validates the SOTI TLS certificate. There is no insecure TLS option.

## Tenant contract preflight

Copy `docs/soti-exchange-access.example.json` outside Git and edit it. Field
paths, status values, `ReferenceId`, endpoint paths and action payload must match
the tenant response and Swagger. Settings with a finite set of values:

- `diagnostic`: `Off | Basic | Verbose`; safe default `Off`.
- `actionContractVerified`: `false | true`; keep `false` until an administrator
  has compared the action path and payload with tenant-local Swagger.

`apply` is blocked while `actionContractVerified` is `false`. Unknown collection
shapes and paginated responses are rejected. For a tenant that paginates
`GET /devices`, narrow `devicesPath` with the exact supported tenant filter. The
tool will not silently process a partial fleet.

Check authentication and read access on one pilot device:

```bash
python3 scripts/soti_exchange_access.py \
  --config /secure/soti-exchange.json \
  auth-check --device-id '<SOTI_DEVICE_ID>'
```

## Inspect, approve, apply, verify

Inspection is read-only:

```bash
python3 scripts/soti_exchange_access.py \
  --config /secure/soti-exchange.json \
  inspect --output /secure/inspection.json
```

Create a separate approval file from selected entries in `candidates`:

```json
{
  "schemaVersion": 1,
  "snapshotId": "COPY_FROM_INSPECTION",
  "approvals": [
    {
      "deviceId": "SOTI_DEVICE_ID",
      "referenceId": "EXPECTED_EXCHANGE_REFERENCE_ID"
    }
  ]
}
```

The report expires after `reportTtlMinutes`. `apply` re-reads every device and
rechecks its corporate group, management mode, active state, `ReferenceId` and
quarantine state before mutation:

```bash
python3 scripts/soti_exchange_access.py \
  --config /secure/soti-exchange.json \
  apply --report /secure/inspection.json \
  --approvals /secure/approved.json \
  --output /secure/apply-result.json --confirm APPLY

python3 scripts/soti_exchange_access.py \
  --config /secure/soti-exchange.json \
  verify --report /secure/inspection.json \
  --approvals /secure/approved.json \
  --output /secure/verification.json
```

`apply` requires a reachable `otlpLogsEndpoint` before the first action. The
current trusted-LAN lab uses OTLP/HTTP JSON with `auth=none`; production routing
can terminate authentication at a deployment-owned collector or proxy. Every
event is also emitted as structured JSON to stdout/stderr. Tokens, passwords,
account data and email fields are masked.

The bundled lab stores these audit events in the Collector persistent volume as
rotated `/var/lib/otelcol/soti-exchange-audit.jsonl` files. This is an operations
sink, not the approval source of truth; the protected local result JSON remains
the operator record for a specific run.

If an action request has an ambiguous transport failure, the tool re-reads the
device once and never blindly repeats the POST. A still-quarantined result is a
manual-review failure.
