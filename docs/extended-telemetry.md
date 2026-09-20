# Battery and storage telemetry (code18)

The existing battery and self-telemetry pipelines carry the new observations. No Room schema change is needed: metric IDs, components, status, reason, provenance and nullable values use the existing records. Existing stored settings acquire the new defaults on upgrade; historical rows are not synthesized.

## Controls and cadence

Battery settings expose additional BatteryManager APIs (average current, remaining energy, charge-time estimate, Android 14 extended charging status). Default current interval remains 60 seconds. Wear and passport retain their daily/weekly cadence.

Self Telemetry / Storage exposes separate process I/O, system I/O and flash wear switches, with a configurable 15–3600 second I/O interval (default 60). The containing Self Telemetry and Storage switches must also be enabled. Flash successes are sampled daily; denied or failed file sources retry after 15 minutes. Start, settings changes and the availability check invalidate access caches.

Every value is read by the APK using its own credentials. ADB or Device Owner capabilities do not imply that this APK has the same access. No privileged shell, root helper, hidden BatteryManager API or synthetic disk load is used.

## Sources and meaning

- Battery: public BatteryManager, including API 28 charge-time prediction and API 34 charging status. Firmware sentinel values remain unavailable. No conversion of charge level or health category to SOH.
- Process I/O: /proc/self/io. Original counters, interval deltas and rates remain separate. Logical bytes/syscalls are not physical IOPS. Kernel write accounting occurs when pages are dirtied and cancelled writes remain separate.
- System pressure: /proc/pressure/io, separated into some and full; cumulative microseconds and interval stall time are not device utilization.
- Block I/O: /sys/block/DEVICE/stat, with /proc/diskstats fallback. Only whole mmcblkN, sdX, nvmeNnN devices are selected. No sums across mapper devices or partitions. Kernel sectors are 512 bytes, independent of logical device block size.
- eMMC: life_time A/B categories (10% lifetime-used bands, 11 beyond estimated life) and pre_eol_info reserve category. Zero is unknown, not healthy. Categories are not an exact health percentage.

A source switch, process restart, reset, pause or observation gap invalidates rate baselines. Per-device components and sources are retained in history and exports. New observations do not automatically change Performance State.

## Presentation

Permanent refusals are collapsed under an accessible bilingual disclosure. Errors, disabled collectors and missing samples retain distinct states. History checks the selected period/session; previous valid measurements remain accessible after permissions change. Numeric unavailable values are never exported as zero.

Battery's last-24-hours summary includes temperature/charge extrema and observed external-power/discharge durations. Only adjacent valid frames in the same session/segment contribute; gaps are excluded. This is coverage of observation, not battery wear.

## Validation without reboot

Run the focused ExtendedTelemetryTest, affected agent/coordinator/CPU tests, locale checker and ExtendedTelemetryAcceptanceTest using the isolated acceptance package. Physical tests use the connected tablet without reboot or USB configuration changes. Capture existing main-app settings/session before installing code18, then resume collection in a new session and verify actual Room and OTLP observations. Real elevated-MDM access remains a separate deployment test.

## Primary references

- [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)
- [AOSP charging states](https://android.googlesource.com/platform/hardware/interfaces/+/android14-release/health/aidl/android/hardware/health/BatteryChargingState.aidl)
- [Linux process I/O](https://docs.kernel.org/filesystems/proc.html)
- [Linux PSI](https://docs.kernel.org/accounting/psi.html)
- [Linux block statistics](https://docs.kernel.org/block/stat.html)
- [Linux MMC tools](https://kernel.googlesource.com/pub/scm/utils/mmc/mmc-utils/)
