# AHWOTel deployment profiles

AHWOTel can export a standalone installation as a versioned JSON deployment profile. The same JSON can be imported manually or delivered in Android managed configurations.

The profile contains collection, retention, Battery, Self telemetry, OEM/Knox, diagnostic and OTLP settings. It never contains the installation `deviceId`, UI language, samples, upload queue, session state, credentials, certificates, or absolute debug expiry. Each device therefore retains its own telemetry identity.

The current schema is `docs/deployment-profile.schema.json`, `schemaVersion` is `4`. `auth` contains `mode` (`none`, default, or `mtls`), `alias`, `trust` (`system`, default, or `custom`) and `trustRef`. Unknown, missing and duplicate fields are rejected. The maximum UTF-8 document size is 128 KiB. Profiles with `desiredState=running` must select continuous collection. Version 1/2/3 profiles must be recreated with an increased revision; existing persisted old-profile management can still be explicitly released. Version 2 added `settings.pauseWhenLocked` (default false) and limited OEM categories to DEVICE, NETWORK, BATTERY and RESOURCES. The primitive MDM key is `pause_when_locked`.

## Manual workflow

1. Configure a reference device.
2. In Settings, enter a stable profile ID and increasing integer revision, then select **Export profile**.
3. Edit and validate the JSON if required. Export always writes `desiredState=stopped` and diagnostics `off`.
4. Import it on a standalone device. A confirmation shows the profile ID and revision. The existing `deviceId`, UI language and local history remain unchanged.

Changing or disabling the OTLP endpoint, or changing authentication mode, alias or server trust clears the pending upload queue; local history remains. A warning is shown with the authentication settings. Renewing a certificate under the same alias preserves the queue.

## Android managed configurations

Apply a profile with these restrictions:

```text
management_action = apply
deployment_profile = <complete JSON document>
```

The app persists the accepted policy. An empty restrictions Bundle, a read failure, process restart, or loss of network does not unlock it. Revision floors are stored independently for every `profileId`; switching to another profile therefore cannot make an older revision acceptable later. The same semantic profile and revision are idempotent even if whitespace or object-key order differs. Changed content at the same revision and older revisions are rejected.

Acceptance and runtime convergence are separate. A profile is marked applied only after its effective settings, OTLP queue boundary and requested collection state have been reached. Re-delivery of the same revision and every process start retry an incomplete `running` or `stopped` transition.

Release management explicitly:

```text
management_action = release
profile_id = <currently active profile ID>
profile_revision = <integer greater than the active revision>
```

After release, the last effective values become editable local settings. The old collector endpoint is not restored.

Do not mix the full-profile keys with the legacy primitive restrictions in one Bundle. Existing primitive restrictions remain supported separately; no automatic format fallback is performed.

Managed mode makes collection controls, settings, profile import/export and SOTI commands read-only. The user can still select English or Russian and inspect/export results and diagnostics. Android Force stop, application removal and clearing app data must be controlled by the MDM device policy; the app cannot override those system actions.

Structured events report policy application, rejection and blocked actions without logging the profile payload. `Basic` and temporary 15-minute `Verbose` diagnostics continue to use the existing stdout/stderr, Logcat and rotating local JSONL sinks.

## mTLS and separate certificate provisioning

Schema 4 also requires `auth.selection` (manual/rule), `auth.delegated` (false by default), and `auth.rule` (templateOid, policyOid, sanUri, issuer, ou). Rule-mode export clears the reference-device alias. Separate managed keys `tls_client_alias`, `tls_client_profile_id`, `tls_client_profile_revision` bind each installation to its own identity; all three must match the current profile/revision. Updating this binding preserves queued measurements and does not change the shared profile's canonical fingerprint. See the EN/RU mTLS guides for purpose markers and delegated access limits.

The shared profile contains `auth.mode=mtls`, server trust reference and a logical client-key alias. The private key and client certificate must be enrolled separately for each device through MDM/PKI and are never exported in this JSON. A public custom CA bundle can be independently provisioned with the `tls_ca_bundle` managed restriction. Missing keys or CA material block export without fallback. Full setup, requirements and operational limits: [English](mtls.md), [Русский](mtls-ru.md).
