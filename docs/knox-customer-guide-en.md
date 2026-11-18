# AHWOTel: customer guide to Samsung Knox

Reviewed 2026-11-18. Knox is integrated into AHWOTel, never into other business applications. Android min/target are 26/36; the build uses Knox SDK 3.13/API 40, not a requirement for every device. Every getter is checked separately.

## Features

Settings → Samsung Knox offers key import, explicit activation, platform/result diagnostics. OEM → Device has total/available internal and external SD-card capacities in bytes. These are not I/O speeds; default collection is daily plus the first session observation (Custom profile can change the interval). Android and Knox remain separate sources. All four getters are deprecated since Knox API 35; firmware may return -1, deny or omit them. Refusals have null values, never fabricated zeros. No official system CPU, raw temperature or battery-wear getter was confirmed; standard Android sources continue independently. See the [API/SDK matrix](knox-api-matrix.md).

## Registration and keys

1. Register a corporate Samsung account and Knox Developer/Partner account, obtain required approval and License keys access.
2. SDK Tools → SDK Downloads → Knox SDK; accept the SDK terms and download 3.13/API 40 for reproducible builds. Operators need only the APK, not a JAR or Android Studio.
3. License keys → My license keys → ACTIONS → Get a license key. Select DEVELOPMENT KEY for a pilot, an appropriate COMMERCIAL KEY and entitlements for production.
4. Associate the actual distributed APK using EXTRACT PACKAGE. Check package/signing identity extracted by the portal. Production is `com.ahwotel`; isolated tests are `com.ahwotel.acceptance` and need their own permitted association.
5. Recheck association after changing package/signing identity or debug-to-production signing. Data-preserving Android upgrades require a compatible signature; plan any signing transition separately.

| Material | Owner/location/use |
|---|---|
| AHWOTel KPE development key | Developer/admin pilot; portal expiry and device quota apply. Local key path: `secrets/ahwotel-dev-knox.key`, Git-ignored. |
| AHWOTel KPE commercial key | Customer/authorized partner production activation of the associated APK; deliver through an access-controlled admin configuration. |
| SOTI/KPE agent licenses | MDM administrator; not evidence of AHWOTel activation or grants. |
| Backwards Compatible Key | Conditional Samsung requirement for Knox 2.7 or earlier/some legacy licenses. This adapter targets Knox 3.x and does not activate BCK; that requires separate integration. |
| mTLS certificate/private key | Separate OTLP PKI; does not replace KPE. See [mTLS](mtls.md). |

A developer account does not authorize every API. Commercial availability, fees, entitlements, quotas and validity depend on current Samsung terms/customer agreements; different KPE offerings must not be reduced to a universal free-license promise.

## Local pilot

Use a supported physical Samsung, stock firmware, correct clock and internet. An emulator cannot prove actual Knox functionality. Prepare one key in a UTF-8 text file up to 4096 bytes. In Settings → Samsung Knox choose Import license file, then Activate Knox license. Only key presence is displayed. Read and confirm Samsung consent/EULA manually if requested; AHWOTel does not accept it or become Device Owner automatically.

API 35+ uses a callback: the UI shows its result code and reported permission count. Older broadcasts only trigger an authoritative activation-record recheck; their extras never prove success. An activation record, last successful callback and actual getter permissions are different facts. Enable OEM/Knox/Device, start collection and inspect values/refusal reasons, (i) help, bottom folded unavailable group, History/ZIP and optional OTLP.

The imported key is stored in private preferences encrypted with AES-GCM/Android Keystore; allowBackup=false. It is excluded from Settings/portable schema 4, history export, telemetry and logs. Import leaves the source SAF document in place: the administrator controls its retention and access. Build/runtime startup do not require a key.

## SOTI fleet deployment

Retain the existing SOTI Device Owner agent. Deploy the signed APK into the intended user/profile and apply the normal portable deployment profile. Deliver the key separately through Android managed application restrictions:

```json
{"knox_license_key":"REPLACE_WITH_CUSTOMER_KPE_KEY","knox_license_action":"activate"}
```

These are separate restrictions, not fields in a portable profile. Use the customer's APK-associated key. SOTI stores this value, Android exposes application restrictions to the app: restrict administrative access to those surfaces. AHWOTel export never includes it. An accepted deployment profile makes settings read-only; a key alone does not lock all settings.

An `activate` action received before the key shows “Waiting for the license key”. Deliver the key with the action still present to start activation. After a confirmed result, repeated delivery of the same key and action does not activate it again. After an interrupted attempt, AHWOTel rechecks Samsung's record and can make one delayed recovery attempt. To retry explicitly, remove `knox_license_action`, wait for the device to apply it, then deliver `activate` again. A new key starts a new attempt. Removing the action keeps the license and the stored managed key. Local import cannot overwrite the managed key. A deployment profile controls the rest of the application settings. License errors are shown separately and do not prevent the monitoring profile from taking effect. Pilot actual grants and getters before broad assignment. Verify the application-restrictions editor and delivery in the customer's SOTI environment.

## Renewal, reinstall and troubleshooting

Before expiry obtain renewal/replacement, recheck association and deliver the replacement key/action. Automatic deactivation is not implemented; follow Samsung's admin procedure for revocation/activation cleanup. Clearing data/reinstall requires import again; install -r with compatible signature preserves private data. Removing a device/app from SOTI does not prove Samsung revoked its activation.

| Symptom | Check |
|---|---|
| Knox unavailable | Samsung model, stock OS, system library and supported platform. |
| Key present but no success | Explicit action/button, device connectivity to KLMS, clock and APK association. |
| Invalid key/signer, expiry, quota, user refusal | Look up the result code in KnoxEnterpriseLicenseManager; verify entitlement/validity/consent. Never include the key in support logs. |
| No result after two minutes | Timeout: check network/consent and explicitly retry. |
| Record exists but values missing | Check PERMISSION_DENIED, METHOD_MISSING and MISSING_VALUE separately; record is not proof of current validity/grants. |
| -1 or no SD card | No numeric measurement, not zero capacity or proof of wear. |

Use current Samsung License Management network requirements for firewall/proxy configuration, retain TLS verification and test from the device network. No unverified fixed domain/IP allowlist is supplied here.

## Build and evidence

Download `.tools/knox/Knox-SDK-3.13-(API-level-40).zip`; SDK archives and `secrets/` stay Git-ignored. `scripts/knox-sdk.sha256` pins the archive. `python3 scripts/prepare-knox-sdk.py` validates it and stages only the JAR in `app/build/knox/`; Gradle invokes this as a compileOnly dependency. Stubs and license keys are not packaged. Missing/mismatched SDK fails the build with a diagnostic.

On SM-T225 / Android 14 / Knox API 37, real dev-key import and activation succeeded (callback code 0). Internal total/available capacities were measured; external capacities had no numeric data. Internal readings were available before activation; successful licensing does not prove a license requirement for these getters. SOTI delivery/roles and older Knox remain **NOT VERIFIED**. See [the SDK matrix](knox-api-matrix.md) and [code39 validation](VALIDATION-knox-code39.md) for evidence and limits.

Official sources: [get/associate keys](https://docs.samsungknox.com/dev/knox-sdk/get-started/get-a-license/), [activation/BCK](https://docs.samsungknox.com/dev/knox-sdk/get-started/activate-your-knox-license/), [selective permissions](https://docs.samsungknox.com/dev/knox-sdk/get-started/declare-permissions/), [licensing](https://docs.samsungknox.com/dev/knox-sdk/introduction/about-licenses/), [API mapping](https://docs.samsungknox.com/dev/fundamentals/sdk-licenses/overview/knox-version-mapping/).
