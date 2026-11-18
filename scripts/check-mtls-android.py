#!/usr/bin/env python3
"""Assert the isolated Android KeyChain mTLS measurement was stored in Prometheus."""
import argparse
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
from otel_lab import ROOT, environment


def validate_run(run, expected, serial):
    if run.get('passed') is not True or run.get('finishedAt', 0) < run['startedAt']:
        raise AssertionError('unsuccessful_run')
    if run['serial'] != serial or not run['runId'] or run['runId'] != expected['runId']:
        raise AssertionError('wrong_run')
    if not run.get('endpoint') or run['endpoint'] != expected['endpoint']:
        raise AssertionError('wrong_endpoint')
    if not expected['deviceId'] or not expected['sessionId']:
        raise AssertionError('missing_identity')
    points = expected['points']
    if len(points) < 6:
        raise AssertionError('incomplete_session')
    if not all(p['time'] > 0 and math.isfinite(p['value']) for p in points):
        raise AssertionError('invalid_point')
    if len({p['time'] for p in points}) != len(points):
        raise AssertionError('duplicate_time')


def matches(expected, result):
    if result.get('status') != 'success': return False
    rows = result.get('data', {}).get('result', [])
    actual = {(round(float(t)*1000), float(v)) for row in rows
              if row.get('metric', {}).get('device_id') == expected['deviceId'] and
              row.get('metric', {}).get('monitoring_session_id') == expected['sessionId']
              for t, v in row.get('values', [])}
    return {(p['time'], float(p['value'])) for p in expected['points']} <= actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--run', required=True, type=Path, help='Current successful instrumentation .run.json')
    args = parser.parse_args()
    output = ROOT/'artifacts/mtls/native-keychain-prometheus.json'
    output.unlink(missing_ok=True)
    env = environment()
    raw = subprocess.check_output([str(ROOT/'scripts/adb.sh'), '-s', args.serial, 'shell', 'run-as',
                                   'com.ahwotel.acceptance', 'cat', 'files/mtls-lab-result.json'], text=True, timeout=10)
    expected = json.loads(raw)
    run = json.loads(args.run.read_text())
    validate_run(run, expected, args.serial)
    first = min(p['time'] for p in expected['points'])
    last = max(p['time'] for p in expected['points'])
    window = (last-first)//1000+3
    query = ('device_memory_available_bytes{device_id='+json.dumps(expected['deviceId'])+
             ',monitoring_session_id='+json.dumps(expected['sessionId'])+'}['+str(window)+'s]')
    url = f"http://127.0.0.1:{env['PROMETHEUS_PORT']}/api/v1/query?"+urllib.parse.urlencode({'query': query, 'time': last/1000+1})
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic()+30
    while True:
        with http.open(url, timeout=5) as response: result = json.load(response)
        rows = result.get('data', {}).get('result', [])
        if matches(expected, result): break
        if time.monotonic() >= deadline: raise AssertionError('native_mtls_point_not_found_in_prometheus')
        time.sleep(1)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'passed': True, 'transport': 'Android KeyChain -> OTLP/mTLS -> Collector -> Prometheus',
                                 'expected': expected, 'series': rows}, indent=2)+'\n')
    print('PASS: current run, all original Android RAM values/timestamps through KeyChain mTLS; points='+str(len(expected['points'])))


if __name__ == '__main__': main()
