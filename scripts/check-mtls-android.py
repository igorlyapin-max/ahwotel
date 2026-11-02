#!/usr/bin/env python3
"""Assert the isolated Android KeyChain mTLS measurement was stored in Prometheus."""
import argparse
import json
import subprocess
import time
import urllib.parse
import urllib.request
from otel_lab import ROOT, environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    args = parser.parse_args()
    env = environment()
    raw = subprocess.check_output([str(ROOT/'scripts/adb.sh'), '-s', args.serial, 'shell', 'run-as',
                                   'com.ahwotel.acceptance', 'cat', 'files/mtls-lab-result.json'], text=True, timeout=10)
    expected = json.loads(raw)
    at = expected['time']/1000
    query = ('device_memory_available_bytes{device_id='+json.dumps(expected['deviceId'])+
             ',monitoring_session_id='+json.dumps(expected['sessionId'])+'}[1m]')
    url = f"http://127.0.0.1:{env['PROMETHEUS_PORT']}/api/v1/query?"+urllib.parse.urlencode({'query': query, 'time': at+10})
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic()+30
    while True:
        with http.open(url, timeout=5) as response: result = json.load(response)
        rows = result.get('data', {}).get('result', [])
        matches = [(float(t), float(v)) for row in rows for t, v in row.get('values', [])
                   if abs(float(t)-at) < 0.001 and float(v) == expected['memoryAvailable']]
        if result.get('status') == 'success' and matches: break
        if time.monotonic() >= deadline: raise AssertionError('native_mtls_point_not_found_in_prometheus')
        time.sleep(1)
    output = ROOT/'artifacts/mtls/native-keychain-prometheus.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'passed': True, 'transport': 'Android KeyChain -> OTLP/mTLS -> Collector -> Prometheus',
                                 'expected': expected, 'series': rows}, indent=2)+'\n')
    print('PASS: original Android RAM value and timestamp stored through real KeyChain mTLS; points='+str(sum(len(row['values']) for row in rows)))


if __name__ == '__main__': main()
