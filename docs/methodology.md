# AHWOTel measurement methodology

AHWOTel supports two related uses:

1. **One-device diagnosis** — inspect current readings, history, source availability and the monitoring agent overhead on a specific Android device.
2. **Fleet study** — measure a representative set of corporate devices in defined business scenarios, deliver observations through OTLP, and estimate whether each homogeneous group has sufficient capacity.

The application records technical evidence. A collector can consolidate fleet data, but neither the APK nor one threshold crossing proves a business outage or its root cause. The study owner must define the business scenario, technical conditions and allowed unavailable time before measurements begin.

## Study design

Use this sequence for every business scenario:

1. Define the work performed, active working hours and the business outcome that must remain available.
2. Define the target population and split materially different devices into strata, for example by model, Android/OEM build, RAM and storage class, managed profile, application version, network environment or shift.
3. Calculate and select a representative sample independently for every stratum that needs a separate conclusion. Prefer random selection; document any substitution or exclusion.
4. Run the same workload and AHWOTel configuration. Record application, OS and policy changes.
5. Convert sustained technical conditions into resource-insufficiency intervals.
6. Merge overlapping intervals and restrict them to the scenario working windows.
7. Compare the resulting duration and affected-device share with the approved business allowance. Report confidence intervals and missing-data coverage.

Repeated time-series observations from one device do not replace independent devices. They characterize that device over time; fleet inference depends on how devices were selected.

## Sample-size model

The in-app calculator is a planning aid for simple random sampling and a normal approximation. It supports:

- **Proportion:** estimate a device share, such as the share exceeding a business allowance.
- **Mean:** estimate an average, such as resource-insufficiency minutes per shift.

For population size `N`, confidence critical value `z`, expected proportion `p`, and margin `e`:

```text
n0 = z² × p × (1 − p) / e²
```

For a mean with pilot standard deviation `σ` and required absolute error `E`:

```text
n0 = (z × σ / E)²
```

The finite-population correction and unusable-data reserve `r` are:

```text
n = ceil(N × n0 / (N + n0 − 1))
planned = min(N, ceil(n / (1 − r)))
```

The calculator uses `z = 1.64485`, `1.95996`, and `2.57583` for 90%, 95%, and 99% confidence. When a proportion is unknown, `p = 50%` is conservative. Estimate `σ` from a pilot in the same stratum. A clustered, paired, weighted or strongly skewed design requires a specialist method.

At 95% confidence, `p = 50%`, and no unusable-data reserve:

| Population | ±5 percentage points | ±3 percentage points |
|---:|---:|---:|
| 100 | 80 | 92 |
| 500 | 218 | 341 |
| 1,000 | 278 | 517 |
| 5,000 | 357 | 880 |
| 10,000 | 370 | 965 |
| 100,000 | 383 | 1,056 |
| Very large | 385 | 1,068 |

NIST describes the same normal-approximation planning formulas and recommends calculating a sample for each stratum. CDC also stresses representative selection and treats sample size as a planning guide that must account for nonresponse and study design: [NIST sample-size selection](https://www.itl.nist.gov/div898/handbook/ppc/section3/ppc333.htm), [NIST confidence intervals for a mean](https://www.itl.nist.gov/div898/handbook/eda/section3/eda352.htm), [CDC Epi Info population survey](https://www.cdc.gov/epiinfo/user-guide/statcalc/samplesize.html).

## Technical resource-insufficiency conditions

Define conditions before collection. Session thresholds and escalation/recovery windows are part of the evidence and may differ from current settings.

### Combined Performance State

A practical primary event can be `performance.state >= 2` (`Degraded` or `Critical`) after filtering. `Warning` may remain an early signal. Performance State is the maximum available CPU, memory, storage and thermal pressure state. If an input is missing, the result is partial rather than proof that all other resources are healthy.

### CPU

- `performance.pressure.cpu >= 2` can be a configured direct shortage condition when `device.cpu.utilization` is readable. Defaults use utilization candidates 80% / 90% / 95%; they are examples, not Android standards.
- `agent.cpu.runqueue_wait` and `agent.scheduling.delay` show that AHWOTel work waited to run. A sustained rise aligned with business latency supports a contention hypothesis but does not alone prove system-wide CPU shortage.
- If `device.cpu.utilization` is `UNSUPPORTED`, indirect signals remain indirect and must not be presented as a utilization measurement.

### Memory

- `performance.pressure.memory >= 2`, `device.memory.low = 1`, or sustained low `device.memory.available_percent` can define insufficiency.
- A corporate policy such as “RAM use must not exceed 60%” means available RAM must remain at least 40%. This is not an AHWOTel default or a universal Android threshold.
- Default AHWOTel available-RAM candidates are 15% / 10% / 5%. Correlate episodes with application restarts, reclaim or business latency where those observations exist.

### Storage and I/O

- `performance.pressure.storage >= 2` or sustained low `device.storage.available_percent` can define capacity insufficiency. Default available-space candidates are 15% / 10% / 5%.
- `device.storage.psi.*` and `device.storage.block.*` can support an I/O-contention hypothesis when Android/OEM permissions expose them. There is no universal PSI or throughput threshold across flash and OEM implementations; establish a baseline per stratum.

### Thermal

- `performance.pressure.thermal >= 2`, sustained `device.thermal.status` or decreasing `device.thermal.headroom` can define a thermal-capacity event. With defaults, thermal status 2 starts a degraded candidate.
- Battery temperature is useful context but is not CPU temperature and does not by itself prove CPU throttling.

### Battery and agent overhead

Battery charge, charging, health, voltage and temperature can explain operational unavailability, such as failing to finish a shift. They do not automatically change Performance State. Define endurance as a separate business outcome when it matters.

`agent.cpu.*`, `agent.memory.pss`, `agent.memory.rss` and self-overhead metrics measure AHWOTel cost and observation delay. Use them to detect observer impact and compare agent versions; do not treat them as total device capacity.

## Time and business decision

Apply escalation/recovery filters, then merge overlapping resource intervals before summing them. Otherwise one minute with simultaneous CPU and memory shortage would be counted twice. `UNSUPPORTED`, permission denial, disabled collection, stale values and gaps are **unknown coverage**, neither Normal nor shortage.

Recommended fleet outputs inside active working windows are:

- share of devices with any shortage;
- affected device-work-time;
- episode count and p50/p95 duration;
- share of devices exceeding the approved allowance;
- measured and unknown time.

An illustrative policy is: “Capacity is sufficient if no more than 5% of devices have over 10 merged minutes at `performance.state >= 2` during a shift, with at least 90% measurement coverage.” These numbers are not defaults. The process owner must approve actual tolerances and confidence before measurement.

Association is not causation. Compare like-for-like strata, investigate outliers, and report uncertainty rather than only a point estimate.
