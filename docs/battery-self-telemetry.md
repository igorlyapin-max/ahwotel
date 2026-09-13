# Battery and Self Telemetry (code 9)

Implementation scope: on-device collection, local aggregation/history, EN/RU UI and optional common OTLP delivery. Source requirements: `self_telemetry_android_TZ.txt`. No new backend, remote configuration server, fleet regression alerts or Battery Impact Score is implemented. These require downstream analytics and population data; an individual device cannot establish fleet battery regressions.

## Settings and lifecycle

Monitor / History → Battery or Self Telemetry opens the corresponding history, graph and help. Settings contains independent Battery and Self Telemetry sections. English remains the initial language; existing Russian selections are retained.

Battery state defaults to 60 seconds, wear to 24 hours, specifications to 7 days. Self sampling defaults to 60/300/900 seconds; its aggregation window and upload interval default to 300 seconds. Battery state uploads every 300 seconds, wear every 24 hours. Collection and upload settings are independent. Monitoring Stop stops new measurements; the partial self window is saved during session finalization. Pending packets may still be delivered. There is no self-only background monitoring service.

Collection follows the main screen-off policy. Slow probes use persisted last-run dates and run at the next permitted collection opportunity. They do not wake the phone. Resuming after a pause, process restart, wall-clock jump or changing effective sampling settings breaks baselines. Debug telemetry uses 10-second fast sampling for 15 minutes, restores normal sampling automatically, and does not enable Verbose logging. Frequency changes do not reset the main session timer.

Current battery and Self battery context share a reading; no duplicate base/OEM battery publication is produced. Existing base/OEM battery history remains readable. The old OEM battery category is historical; its settings moved to Battery. Standard Android battery data does not depend on OEM being enabled.

## Meaning and availability

`AgentMetric.kt` is the catalogue. Every entry has eight localized help sections, validated by `scripts/check-locales.py`. `scripts/generate-agent-metrics.py` regenerates catalogue resources.

- Battery readings describe the entire device. Android health `GOOD` is a diagnostic enum, not remaining capacity or SOH.
- Wear uses Android cycle count when provided (API 34+), or readable standard sysfs `cycle_count`, `charge_full`, `charge_full_design`. SOH from full/design is marked ESTIMATED. Samsung-specific `fg_*` fields are probed diagnostically but undocumented units/semantics are not converted into values.
- Having Knox does not grant ordinary APK access to Knox Asset Intelligence or privileged battery fields. There is currently no verified local Knox battery-wear getter in this application. Unsupported/missing/permission-denied sources remain explicit.
- On SM-J260F the API current scale is unverified, so CURRENT is UNAVAILABLE/UNVERIFIED_UNITS. A zero charge counter with a positive charge level is invalid. Neither is used to compute battery capacity or charge drain.
- Device discharge rates require 15 minutes of valid observations after a 5-minute stabilization interval without external power. Power transitions, gaps, counter increases and clock jumps invalidate the relevant calculation. Percentage drain does not require a supported charge counter.
- Process CPU is normalized to one core: 100% can be exceeded. Component operation durations include waiting and are not attributed CPU. Process CPU attribution to foreground/background follows observed Activity callbacks.
- UID network totals are distinct from payload sizes. Upload attempts include retries; partial OTLP success does not count as full success. Compression ratio is compressed/raw.
- Only the agent-owned `AHWOTel:Monitoring` wakelock is tracked, including timeout. Configurable warning thresholds do not assert a universal Android limit.
- OOM and actual device wakeups are UNSUPPORTED without a verified source; a process restart or job start is never substituted. GC uses ART counters when exposed. Memory-pressure events represent callbacks received by this process.
- No new metrics automatically affect Performance State. No metric claims measured application energy in mAh.

Reference contracts: [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager), [Android Debug runtime statistics](https://developer.android.com/reference/android/os/Debug#getRuntimeStat(java.lang.String)), [Linux power supply units](https://www.kernel.org/doc/html/latest/power/power_supply_class.html), [Knox Asset Intelligence battery limitations](https://docs.samsungknox.com/admin/knox-asset-intelligence/dashboard/battery-insights/battery-health/).

## Data and delivery

Room migration 3→4 adds telemetry_records, telemetry_schedule, and stream/dueAt/compressed to the outbox. Existing sessions, samples, OEM history, settings and queued payloads are preserved. New settings get explicit defaults; existing retention and queue settings remain. No destructive migration or additional legacy API.

Records contain observation/window timestamps, actual elapsed duration, component, source, status, reason and quality. Resource metadata includes random installation ID, existing device ID, version/build, Android SDK/version, manufacturer/model/product, timezone and effective measurement configuration hash. Secret endpoints/payload content/user messages are excluded.

Gauge moments are exact. Quantiles use an exact sample set up to 512 observations and a bounded uniform reservoir beyond that, marked ESTIMATED. Counter deltas use window sums. Components are separate chart series. State values retain their observed time; consumers must not interpret a sampled state as an exact continuous trace. Runtime state durations come from lifecycle observations. Self records are available after a window or Stop.

All history uses common retention (14 days by default), subject to total disk quota. Self history has an additional approximate logical budget (20 MiB default, configurable 10–50 MiB); SQLite pages/WAL still count against the total physical quota. Quota cleanup is oldest-first and counts dropped records. Sampling status is never replaced by zero.

When OTLP is off, no network requests or outbox growth are caused by the new groups. Enabling OTLP queues newly collected windows only, not historical backfill. Existing HTTPS endpoint and global switch apply to all groups. Packets are bounded OTLP protobuf with gzip; retries retain original bytes. Individual point timestamps and units are preserved. Window sum/count/min/max/P50/P95 are exported as explicitly named gauges, not falsely advertised as cumulative counters.

A future or retrying packet does not block another ready stream. Expiry is max(createdAt + configured queue age, dueAt + one hour) for every packet, including already queued packets. Retries do not extend expiry; byte quota remains authoritative. Short queue settings remain valid when upload intervals are longer. Android WorkManager may delay delivery; intervals are desired earliest dispatch times, not exact alarms. Disabling OTLP or changing endpoint retains the existing queue-clear behavior.

## Cost and limitations

AgentCosts accumulates a bounded set of component counters. Internal self serialization/upload paths do not recursively instrument themselves; their own cost/volume is accounted through dedicated deferred counters. Error/source transitions are rate-limited by status change. Main diagnostic Off/Basic/temporary Verbose and stdout/stderr + Logcat/optional JSONL are preserved.

Self CPU fraction is INCOMPLETE: synchronous sampling thread CPU / process CPU, with a minimum denominator. Async persistence and scheduling CPU cannot be attributed exactly. It must not be used as proof of the complete 5% overhead objective. Controlled off/on comparisons include those effects, but short on-device experiments are preliminary. CPU, PSS, wakelock and network budgets from the TZ remain product targets, not universal Android limits.

Metrics depending on a backend (fleet comparisons, regression significance, overnight population baselines, impact score, remote sampling) are outside this APK increment. Local exports retain version/configuration/model dimensions for future collector-side analysis.

## Code 9 review corrections

Existing history is retained with its original values and build metadata; corrected calculations apply to new observations. WAKE_MAX uses a maximum, not a mean. Intentional screen-off pauses do not count as scheduler delay or missed work. Each battery counter has its own baseline and a rise relative to the previous sample restarts only that counter window. Missing counter values also restart that window.

Configuration hash revision 2 uses the immutable START collector snapshot (including interval, thresholds and indirect CPU) together with effective live collection/upload settings. Changing next-session defaults or UI language does not relabel running measurements.

Battery/Self charts page through the entire requested range and retain up to 240 time buckets per component. When the metric or range changes, only the matching query result is displayed; a loading indicator replaces stale points. Empty history uses a stable time anchor. Tap selection retains epoch-millisecond precision, including historical device clocks. Gauge means are weighted by observation count; SUM and MAX have explicit labels. Nominal state axes contain only observed codes, without fractional intermediate states. Buckets crossing an unavailable sample or session/segment boundary are marked as gaps. Multiple nominal states within one bucket are marked as mixed; no exact state residence duration is inferred. Source help contains the full exported metric identifier. System Back closes a dialog/menu first, then returns to the parent Monitor/History screen.
