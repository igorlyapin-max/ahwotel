# Validation: Windows PowerShell 5.1 SOTI Exchange access tool

Date: 2026-10-22

## Scope

The validation covers the separate Windows PowerShell 5.1 source, its shared
configuration and artifact contract, and regression safety for the Python and
PowerShell 7 implementations. Android runtime and APK metadata are unchanged.

## Completed checks

The PowerShell 7 parser reports zero syntax errors for both 5.1 files. The Python
suite statically rejects PowerShell 6/7-only APIs in the 5.1 source and checks the
required .NET Framework JSON, SHA-256, NTFS ACL, TLS 1.2 and runtime-guard
contracts. The completed regression evidence is `34 Python tests: PASS` and
`45 PowerShell 7 assertions: PASS`. Running the 5.1 CLI under PowerShell Core
fails closed with `windows_powershell_5_1_required` and exit code 2.

## Pending Windows runtime gate

A Windows host with Windows PowerShell 5.1 is not currently available. Before the
5.1 variant is marked runtime-verified, run:

```powershell
$PSVersionTable
powershell.exe -NoLogo -NoProfile -File .\scripts\test_soti_exchange_access_5_1.ps1
```

Then run the local trusted-HTTPS scenario through
`auth-check -> inspect -> apply -> verify`. Evidence must include the runtime
version, assertion count, one `AllowExchangeAccess` POST, final allowed status,
cross-runtime artifact reads, private NTFS ACLs and OTLP audit delivery without
raw device identifiers or secrets.

Live SOTI access remains a separate tenant gate. Keep
`actionContractVerified=false` until the tenant-local Swagger contract and a
single-device pilot have been confirmed.
