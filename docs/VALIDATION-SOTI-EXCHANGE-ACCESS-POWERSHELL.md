# Validation: PowerShell SOTI Exchange access tool

Date: 2026-10-22

## Scope

The validation covers the standalone PowerShell 7.2+ implementation, shared
configuration and artifact compatibility with the Python implementation,
fail-closed action behavior, diagnostics and OTLP audit delivery. Android runtime
behavior, APK metadata and the Room schema are unchanged.

## Runtime

The tests use the official PowerShell 7.4.20 LTS Linux x64 archive in the ignored
`.tools` directory. Its SHA-256 was verified before extraction:

```text
ED8008345C7F5337F5E293145F5E243CE132E5EE4DFC24AC551C45A4B3092181
```

No PowerShell runtime files are included in Git.

## Automated checks

```text
pwsh -NoLogo -NoProfile -File scripts/test_soti_exchange_access.ps1
38 PowerShell assertions: PASS

python3 -m unittest discover -s scripts -p 'test_*.py'
30 tests: PASS

PowerShell parser diagnostics
0 errors
```

The shared fixture proves identical Python/PowerShell `policyFingerprint` and
`snapshotId`, including non-ASCII configuration content. Focused scenarios cover
corporate eligibility, ordinal string matching, group boundaries, duplicate
Exchange records, report expiry, policy changes, duplicate approvals,
idempotency, ambiguous POST without retry, required audit sink, recursive
redaction, atomic `0600` output and Unix secret-file permissions.

## Local HTTPS end-to-end scenario

An isolated HTTPS mock with a temporary trusted certificate exercised the real
PowerShell CLI and HTTP client:

```text
auth-check -> inspect -> apply -> verify
candidate=1
AllowExchangeAccess POST=1
apply outcome=allowed
verification allowed=true
OTLP audit POST=11
raw device ID in audit=false
inspection/apply/verification modes=0600
```

The Python implementation accepted the inspection artifact produced by
PowerShell, and PowerShell accepted an inspection artifact produced by Python.
The mock process, certificate, secrets and artifacts were kept outside the
repository and removed from the runtime after the scenario.

## Remaining live gate

Live SOTI authentication and `AllowExchangeAccess` were not attempted because
tenant credentials and a confirmed tenant-local Swagger contract are unavailable.
Keep `actionContractVerified=false` until the existing single-device pilot gate
has passed. Windows NTFS ACL behavior also requires a Windows PowerShell 7.2+
host for final platform confirmation.
