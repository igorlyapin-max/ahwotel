# OTLP mutual TLS (mTLS)

[Русский](mtls-ru.md)

AHWOTel supports **None** (default) and **mTLS** authentication for OTLP HTTP/protobuf. None can use verified HTTPS; plain HTTP requires the explicit test-lab switch. mTLS always requires HTTPS, a client key accessible through Android KeyChain, and verified server trust and hostname. Failure never downgrades to None, HTTP or disabled certificate validation. Collection and local history continue when export fails; existing queue age/size limits and retries still apply.

## Configure a pilot

1. Provision a distinct client identity on the device. A PKCS#12 file contains the private key and certificate chain: import it through Android credential installation for **VPN and apps**, or through MDM. Securely obtain its password; do not put it in the deployment JSON. AHWOTel does not import or export private keys itself.
2. Settings → OTLP export → Authentication → **mTLS**. Select **Choose client certificate** and grant this app access. A certificate appearing in Android settings alone does not grant AHWOTel access to its key. The alias can also be entered manually after MDM grants access.
3. Set `https://<server name or IP>:4318/v1/metrics`. The exact DNS name or IP must be in the server certificate SAN.
4. Select system server trust for a chain trusted by Android's system trust store. For a private CA select **Imported server CA**, import a public PEM/DER CA bundle (maximum 64 KiB), and retain its SHA-256 reference. This trust is app scoped; importing a client identity or a Wi-Fi CA does not automatically establish server trust. A custom bundle replaces system trust for this transport.
5. **Check connection** works with the current draft. It displays the public subject, issuer and validity dates; validates client purpose/time, signs a random challenge to check key usability, and sends an empty OTLP metrics request. These public certificate details are not logged. Success proves TLS and OTLP response acceptance, not persistence of measurement points. Enable OTLP and save; check a real device/session time series in Prometheus/Grafana to prove delivery.

Saving a different endpoint, authentication mode, alias or trust/reference cancels requests and clears the upload queue. Local history remains. Renewing the certificate under the same alias does not clear the queue: each request loads the current key/chain and uses a fresh connection. A request already accepted by the server cannot be recalled.

## Certificate requirements and a PKI template

| Item | Client identity | Server identity |
| --- | --- | --- |
| X.509 role | End entity, `CA:FALSE` | End entity, `CA:FALSE` |
| Extended Key Usage | Explicit `Client Authentication` (`1.3.6.1.5.5.7.3.2`) | `Server Authentication` (`1.3.6.1.5.5.7.3.1`) |
| Key Usage | `digitalSignature` if KU is present; recommended explicit | Signing suitable for TLS |
| Algorithm | RSA or EC supported by Android KeyChain/TLS provider | Compatible with client TLS provider |
| Validity | Entire supplied chain valid at current device time | Chain valid at current time, SAN matches endpoint |
| Trust | Collector must trust client issuing CA and receive intermediate certificates | Android system trust or explicitly imported CA bundle |
| Private key | Present in the same Android user/work profile as AHWOTel; selected/granted to its package | Readable by the non-root Collector user |

For a new enterprise template use RSA 2048+ or EC P-256+, SHA-256 or stronger signatures, a distinct per-device key, explicit `digitalSignature`, `CA:FALSE`, `clientAuth`, and the issuing intermediate chain. The key must allow background signing without a PIN/biometric prompt for every operation; otherwise export will wait for a usable identity while local collection continues. Use an immutable inventory identifier in subject/SAN only if your PKI policy requires it; AHWOTel does not require a particular CN or email. Prefer non-exportable, hardware-backed generation through the device/MDM; verify enrollment support separately. A shared CA and template are appropriate; a shared fleet private key is not.

Wi-Fi EAP-TLS may use a certificate with the same `clientAuth` purpose, so a Wi-Fi **template** can sometimes be adapted. A Wi-Fi **credential entry** often belongs to the Wi-Fi keystore, another Android profile, or an access policy that prevents apps from signing. A CA/root entry contains no client private key. Reuse is possible only if the EKU, validity, key access, server trust and enterprise policy all permit it. The existing Samsung certificate has not been proven suitable until a connection test from AHWOTel succeeds. Prefer a separate telemetry template/identity with independent renewal and revocation.

## SOTI / managed fleet

### Certificate rules (schema 4 / code33)

Settings supports **Manual certificate** and **Certificate rule**. Rules combine all nonempty conditions with AND: AD CS template OID (extension `1.3.6.1.4.1.311.21.7`), certificate policy OID (`2.5.29.32`), exact purpose SAN URI, issuer DN and Subject OU. At least one template/policy/URI purpose marker is mandatory for mTLS rule mode; issuer or clientAuth alone is insufficient. Use an organization-approved telemetry template/policy. Create rule from a selected certificate copies its template OID, otherwise its first policy OID: inspect that value before using it across the fleet. Unique Subject CN/SAN values, serials and fingerprints are never copied automatically.

The schema 4 `auth` object additionally requires `selection` (`manual`, default, or `rule`), `delegated` (false by default), and `rule` with string fields `templateOid`, `policyOid`, `sanUri`, `issuer`, `ou`. In rule mode portable `alias` is always empty. Local old settings migrate to manual selection; schema 1–3 deployment documents must be exported again.

SOTI provides these separate Android managed restrictions, alongside the shared profile or in a later update:

```text
tls_client_alias = <the per-device certificate alias>
tls_client_profile_id = <active profileId>
tls_client_profile_revision = <active integer revision>
```

All three fields are required together. A stale/missing binding blocks export. SOTI's `%CertificateTemplate_<Macro-Template-Name>%` is a candidate for alias substitution; test its actual output in SOTI 2026.2.2.1001. Literal unresolved macros are rejected. Binding updates are independent of profile revision: renewal can update the alias without changing a shared profile or clearing queued measurements. Rule/trust/endpoint changes clear the queue; local history remains. The application uses a supplied alias, not unrestricted system-store enumeration.

For unattended access, first try a grant provided by the Device Owner. Alternatively enable **Allow delegated key access** in the shared profile and assign **Certificate Selection** to AHWOTel through SOTI. Self-grant requires Android 11+, an active full managed profile, a matching binding, the policy flag and actual `delegation-cert-selection`. It grants access only to its own package; it reads and validates the certificate before signing and revokes a newly obtained grant if validation fails. The receiver does not select certificates or grant access for other applications. Android allows one delegated chooser: returning no automatic choice for other apps can change their previous silent VPN/SSO flow. A real SOTI/VPN/SSO pilot is mandatory before deployment; exact console support for APK distribution remains unverified.

Startup, settings/KeyChain changes and each upload validate access, purpose, validity and rule. KeyChain notifications cancel existing requests; connection tests expose localized reasons. No background chooser prompt or downgrade is used in managed rule mode. Without delegation an already granted identity remains usable. To stop access, revoke the key grant and disable automatic grant in policy or remove delegation, otherwise an authorized subsequent check can restore it. Offline measurements and bounded retries continue.

Provision the per-device key/certificate separately, then arrange an Android KeyChain grant to `com.ahwotel` in the same profile. A device/profile owner can use the relevant DevicePolicyManager key-pair APIs; availability depends on Android version, admin role and SOTI capabilities. An alias string in a managed configuration never grants access by itself. Confirm the exact SOTI enrollment/grant workflow in the customer's environment; do not assume that installing a Wi-Fi payload exposes its key to an app.

Export/import uses schema **4**. `auth` is separate from `settings`:

```json
{"mode":"mtls","alias":"ahwotel-client","trust":"custom","trustRef":"<64 lowercase hexadecimal SHA-256 characters>","selection":"manual","delegated":false,"rule":{"templateOid":"","policyOid":"","sanUri":"","issuer":"","ou":""}}
```

Allowed modes: `none` (default), `mtls`. Trust: `system` (default), `custom`. A None export uses empty alias/reference unless previously configured. In a fleet use the same logical alias on each device, pointing to a different device key. The shared profile contains no key, certificate bytes, PKCS#12 password or installation `deviceId`.

Deliver server CA material independently through the app's **tls_ca_bundle** managed string restriction, either alongside `management_action=apply`/`deployment_profile` or as a CA-only update:

```json
{"ref":"<SHA-256 of the exact UTF-8 PEM bytes>","pem":"-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----\n"}
```

Bundle maximum is 64 KiB after UTF-8 encoding of `pem`; JSON envelope maximum is 140,000 bytes. Include only public CA certificates. Generate `ref` over the exact bytes (line endings and final newline matter). Material is stored by hash in private app storage and survives a normal `adb install -r`. Wrong hash, non-CA certificates or oversize data are rejected. Missing referenced material blocks export; the app does not widen trust. Accepted full profiles keep settings read-only, including authentication/CA import; inspecting results, language and connection checks remain available. Reissue schema 1/2/3 profiles as schema 4 with an increasing revision; old formats are rejected for application, while their authenticated stored management envelope can still be explicitly released.

## Administrator transport and Android policy

Local Check connection in rule mode validates the selected reference key without MDM grants. Managed uploads still require a separate accepted device binding. Asynchronous rule extraction preserves newer edits and ignores obsolete results. Binding replacement and sender generation changes share one lock; binding-only rotation retains queued telemetry.

For current Android-to-Prometheus acceptance, run instrumentation with `--mtls-lab-endpoint https://127.0.0.1:14318/v1/metrics`, then `python3 scripts/check-mtls-android.py emulator-5554 --run artifacts/review-fixes36/android.run.json` (use the manifest beside your report). The runner generates the run ID; the checker requires successful instrumentation and matches every original Room point for that run/session. Older result files are not supported. Use an isolated Collector with the current generated test APK certificates and ADB reverse; stop it and remove the reverse after testing.

The administrator chooses transport protection. `allowHttp` is false by default; enabling it explicitly permits unencrypted telemetry and is an accepted deployment risk (ASPM 114496), not a false positive. None means no client certificate authentication: it can still use HTTPS with normal server verification. mTLS always requires HTTPS and never downgrades to HTTP.

Android 8+ remains supported. Empty MainActivity task affinity and disabled task reparenting are additional hardening, not a complete StrandHogg 2.0 fix (ASPM 114497). `targetSdk=36` does not patch an old OS. Administrators must check vendor security updates through MDM; unpatched old devices retain a platform risk. See [Android's guidance](https://developer.android.com/privacy-and-security/risks/strandhogg), which identifies Android 11+ as containing the required fixes. Standard launch mode is retained for system file and KeyChain dialogs.

## Local Collector → Prometheus → Grafana lab

The credential generator is a local operator/build tool, not a remotely exposed upload handler (ASPM 120211). Its output directory is deliberately selectable. It rejects `..`, symlinks/junctions in existing path components, files in place of directories, and nonempty outputs. On POSIX the output directory must belong to the current user with no group/other permissions; new directories use `0700`. Use a trusted parent directory: validation is not a sandbox against another process that can concurrently replace parent directories. The generator never changes permissions on an existing directory to make it acceptable, and never overwrites existing keys. Smoke tests use private temporary directories; Gradle uses ignored generated test assets.

Prerequisites: existing [lab setup](../deploy/otel-lab/README.md), OpenSSL, correct clock and `LAB_BIND_ADDRESS`. Run from the repository root:

```bash
./scripts/otel-lab.sh up                         # default None on a fresh .env
./scripts/otel-lab.sh certs                      # never overwrites an existing key directory
stat -c %g deploy/otel-lab/.runtime/mtls/server.key
```

Set `OTLP_AUTH_MODE=mtls` and `MTLS_CERT_GID=<the numeric group above>` in `deploy/otel-lab/.env`, then:

```bash
./scripts/otel-lab.sh up --collector-only
```

On first deployment use `up` without `--collector-only` to start all components. Switching authentication recreates only Collector when `--collector-only` is used; volumes and Prometheus/Grafana remain. The same published port 4318 now accepts only HTTPS with a client certificate. There is no simultaneous plain HTTP receiver. Grafana's anonymous Viewer and local health/admin ports are separate; mTLS protects ingest, not the dashboard.

Local credentials are generated under ignored `.runtime/mtls/`: `ca.crt`, `server.crt`, `server.key`, `client.crt`, `client.key`, `client.p12`, `client-password.txt`, and the test CA key. Server SAN includes the configured address, `localhost`, and `127.0.0.1`. CA is valid 30 days, leaves 14 days. Only server key/certificate and public client CA are mounted in Collector; the CA private key is never mounted. Protect the directory; copy the test P12 and its password to the test device through a controlled channel, install it for VPN/apps, import `ca.crt` into AHWOTel and select the client alias. These test keys are for a lab, not a fleet PKI. Changing the lab IP requires a correctly reissued server certificate; `certs` refuses overwrites intentionally.

For renewal mount the replacement server chain/key and reload Collector. Reprovision a renewed client under the same alias/grant. During CA rotation provision a public overlap bundle and update profile reference/revision; this trust change clears queued uploads. Retain the original CA privately if more leaf certificates are needed; never send it to devices. To return to the lightweight lab set `OTLP_AUTH_MODE=none`, reload Collector and explicitly select None/HTTP in the app.

```bash
python3 scripts/test_mtls_lab.py
./scripts/gradle.sh :app:testDebugUnitTest --tests com.ahwotel.MtlsTest --tests com.ahwotel.LabWireFixtureTest --rerun-tasks
./scripts/otel-lab.sh smoke
python3 scripts/mtls_lab_smoke.py
```

`smoke` uses the APK serializers' generated protobuf fixtures and proves their numeric values/timestamps in Prometheus. `mtls_lab_smoke.py` additionally requires mTLS, rejects missing/wrong client identities, wrong server trust and plaintext, and writes sanitized evidence. The in-app connection button alone is not this acceptance check.

The isolated `MtlsAcceptanceTest` additionally tests native Android TLS, queue changes and EN/RU. Its KeyChain enrollment test runs only on an emulator: temporary test credentials are generated with Python/OpenSSL during `assembleAcceptanceAndroidTest` and included only in the test APK. It installs a VPN/apps identity and grants real KeyChain access through system dialogs. The optional `scripts/instrument.sh --mtls-lab-endpoint https://127.0.0.1:14318/v1/metrics` continues with an actual six-second RAM collection; this requires a separate test Collector using exactly the generated test server identity/client CA and an ADB reverse port. It must not be pointed at an unrelated or production PKI. Evidence and tested boundaries: [code32 validation](VALIDATION-mtls-code32.md).

## Failures, diagnostics and limits

Missing/denied key: select again or repair the MDM grant. Expired/not-yet-valid: repair time/enrollment. Invalid usage: reissue with clientAuth/signing. Missing CA: provision the referenced public bundle. Hostname error: fix SAN/endpoint. Handshake error: inspect server/client chains, trusted CAs and Collector access policy. HTTP status/invalid OTLP response: TLS may have succeeded but OTLP did not accept the request. The uploader logs bounded diagnostic codes through existing stdout/stderr, Logcat and JSONL sinks; debug defaults Off, Basic and temporary Verbose remain available. It never logs private keys, PEM payloads or passwords.

mTLS authenticates possession of a certificate accepted by the listener. This first version does not authorize individual client subjects or bind OTLP `device.id` to certificate identity. Every trusted client can claim payload attributes; corporate inventory admission and per-device authorization need an ingress/PKI policy. Automatic CRL/OCSP enforcement and automated enrollment are not implemented. Revoke grants/keys or update listener trust/access policy according to operational requirements; removing one device from SOTI alone does not prove immediate server rejection of its previously issued certificate.

Sources: [Android KeyChain](https://developer.android.com/reference/android/security/KeyChain), [DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager), [Collector TLS configuration](https://github.com/open-telemetry/opentelemetry-collector/blob/main/config/configtls/README.md).
