#!/usr/bin/env python3
"""Read-only, bounded soak observation. Never restarts services or alters telemetry."""
import argparse
import datetime
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request
from otel_lab import environment, runtime_status, ROOT


def sample(env, http, device_id, baseline):
    row = {'time': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'errors': []}
    try:
        runtime = runtime_status(env)
        row['runtime'] = runtime
        row['errors'].extend(runtime['errors'])
        if not runtime['errors']:
            identity = {k: tuple(v[field] for field in
                ('container_id', 'started_at', 'image_id', 'restarts', 'child_generation'))
                for k, v in runtime['containers'].items()}
            if baseline is None:
                baseline = identity
            elif identity != baseline:
                row['errors'].append('unexpected_runtime_change')
    except Exception:
        row['errors'].append('runtime_status_unavailable')
    # Check independent endpoints even when one component is unavailable.
    try:
        query = 'max(ts_of_last_over_time(device_memory_available_bytes{device_id=' + json.dumps(device_id) + '}[30m]))'
        url = f"http://127.0.0.1:{env['PROMETHEUS_PORT']}/api/v1/query?" + urllib.parse.urlencode({'query': query})
        with http.open(url, timeout=15) as response:
            result = json.load(response)
        value = result['data']['result']
        row['sample_age_seconds'] = time.time() - float(value[0]['value'][1]) if value else None
        if row['sample_age_seconds'] is None or row['sample_age_seconds'] > 300:
            row['errors'].append('telemetry_stale')
    except Exception:
        row['errors'].append('telemetry_query_unavailable')
    try:
        with http.open(f"http://{env['LAB_BIND_ADDRESS']}:{env['GRAFANA_PORT']}/api/health", timeout=5) as response:
            if response.status != 200:
                row['errors'].append('grafana_not_ready')
    except Exception:
        row['errors'].append('grafana_not_ready')
    return row, baseline


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hours', type=float, default=24)
    parser.add_argument('--device-id', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/recovery-code16/soak')
    args = parser.parse_args()
    if not 0 < args.hours <= 168:
        parser.error('hours must be in (0,168]')
    env = environment()
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'samples.jsonl').exists() or (out / 'result.json').exists():
        parser.error('output_already_contains_observations; use a new directory')
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    start = time.time()
    deadline = time.monotonic() + args.hours * 3600
    baseline = None
    failures = 0
    queue_first = None
    queue_last = None
    samples = 0
    while True:
        row, baseline = sample(env, http, args.device_id, baseline)
        queue = row.get('runtime', {}).get('queue')
        if queue is not None:
            queue_last = queue['size']
            if queue_first is None:
                queue_first = queue_last
        samples += 1
        failures += bool(row['errors'])
        with (out / 'samples.jsonl').open('a') as f:
            f.write(json.dumps(row) + '\n')
        done = time.monotonic() >= deadline
        if done and queue_last is not None and queue_first is not None and queue_last > queue_first:
            failures += 1
        summary = {'state': ('PASS' if failures == 0 else 'FAIL') if done else 'RUNNING',
                   'started': start, 'elapsed_seconds': time.time() - start, 'required_hours': args.hours,
                   'samples': samples, 'failed_samples': failures, 'queue_first': queue_first, 'queue_last': queue_last}
        temporary = out / 'result.tmp'
        temporary.write_text(json.dumps(summary, indent=2) + '\n')
        temporary.replace(out / 'result.json')
        if done:
            print(json.dumps(summary))
            raise SystemExit(1 if failures else 0)
        time.sleep(min(60, max(0, deadline - time.monotonic())))


if __name__ == '__main__':
    main()
