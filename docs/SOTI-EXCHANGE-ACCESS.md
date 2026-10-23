# SOTI Exchange access operator tool

`scripts/soti_exchange_access.py`, `scripts/soti_exchange_access.ps1` and
`scripts/soti_exchange_access_5_1.ps1` are standalone one-shot administrative
tools. They run on an operator workstation or automation VM that can reach the
SOTI MobiControl API and the OTLP audit endpoint. They do not run on Android, do
not use ADB or Knox, and do not connect directly to Exchange. Use the first
PowerShell script with PowerShell 7.2+ and the `_5_1` script with the built-in
Windows PowerShell 5.1. Neither PowerShell variant requires Python or additional
modules.

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

PowerShell uses the same environment variable names. On Windows, create secret
files on NTFS and remove inherited access for broad identities such as `Users`,
`Authenticated Users` and `Everyone`. The script rejects a secret file when one
of those identities has read access. Do not put either secret directly in the
command line, script or JSON configuration.

## Tenant contract preflight

Copy `docs/soti-exchange-access.example.json` outside Git and edit it. Field
paths, status values, `ReferenceId`, endpoint paths and action payload must match
the tenant response and Swagger. Settings with a finite set of values:

- `diagnostic`: `Off | Basic | Verbose`; safe default `Off`.
- `actionContractVerified`: `false | true`; keep `false` until an administrator
  has compared the action path and payload with tenant-local Swagger.

`exchangeReferenceIds` is optional. Omit it or use an empty array to accept the
only `ExchangeOnlineEmailAccess` record on an otherwise eligible device. Its
actual `ReferenceId` is bound into the inspection, approval and action. A
non-empty list remains an exact allowlist. More than one matching record is
ambiguous and produces a manual-review decision.

`otlpLogsEndpoint` is also optional. Omit it or use `null` for structured
stdout/stderr only. An empty string or malformed URL is rejected.

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

PowerShell equivalent:

```powershell
pwsh ./scripts/soti_exchange_access.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command auth-check -DeviceId '<SOTI_DEVICE_ID>'
```

Windows PowerShell 5.1 equivalent:

```powershell
powershell.exe -NoLogo -NoProfile -File .\scripts\soti_exchange_access_5_1.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command auth-check -DeviceId '<SOTI_DEVICE_ID>'
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

The same workflow in PowerShell is:

```powershell
pwsh ./scripts/soti_exchange_access.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command inspect -Output C:\secure\inspection.json

pwsh ./scripts/soti_exchange_access.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command apply -Report C:\secure\inspection.json `
  -Approvals C:\secure\approved.json `
  -Output C:\secure\apply-result.json -Confirm APPLY

pwsh ./scripts/soti_exchange_access.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command verify -Report C:\secure\inspection.json `
  -Approvals C:\secure\approved.json `
  -Output C:\secure\verification.json
```

For Windows PowerShell 5.1, keep the same arguments and select the dedicated
script:

```powershell
powershell.exe -NoLogo -NoProfile -File .\scripts\soti_exchange_access_5_1.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command inspect -Output C:\secure\inspection.json

powershell.exe -NoLogo -NoProfile -File .\scripts\soti_exchange_access_5_1.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command apply -Report C:\secure\inspection.json `
  -Approvals C:\secure\approved.json `
  -Output C:\secure\apply-result.json -Confirm APPLY

powershell.exe -NoLogo -NoProfile -File .\scripts\soti_exchange_access_5_1.ps1 `
  -Config C:\secure\soti-exchange.json `
  -Command verify -Report C:\secure\inspection.json `
  -Approvals C:\secure\approved.json `
  -Output C:\secure\verification.json
```

Python, PowerShell 7 and Windows PowerShell 5.1 use the same schema version and
canonical hashing rules. An inspection report produced by any implementation can
be approved, applied and verified by another without rewriting the artifact.

Every event is emitted as structured JSON to stdout/stderr. When
`otlpLogsEndpoint` is configured, the tool additionally sends OTLP/HTTP JSON. A
delivery failure emits `audit_sink_unavailable` to stderr and does not block the
approved action. The current trusted-LAN lab uses `auth=none`; production routing
can terminate authentication at a deployment-owned collector or proxy. Tokens,
passwords, account data and email fields are masked.

The bundled lab stores these audit events in the Collector persistent volume as
rotated `/var/lib/otelcol/soti-exchange-audit.jsonl` files. This is an operations
sink, not the approval source of truth; the protected local result JSON remains
the operator record for a specific run.

If an action request has an ambiguous transport failure, the tool re-reads the
device once and never blindly repeats the POST. A still-quarantined result is a
manual-review failure.

Run the focused PowerShell checks with:

```powershell
pwsh -NoLogo -NoProfile -File ./scripts/test_soti_exchange_access.ps1
```

Run the Windows PowerShell 5.1 checks on Windows with:

```powershell
powershell.exe -NoLogo -NoProfile -File .\scripts\test_soti_exchange_access_5_1.ps1
```

The 5.1 test verifies the shared parity fixture, policy and snapshot hashes,
fail-closed action rules, redaction, OTLP responses, atomic output and NTFS ACLs.
PowerShell older than 5.1 and running the `_5_1` CLI under PowerShell Core are not
supported.
