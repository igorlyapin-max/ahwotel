#!/usr/bin/env python3
"""Destructive scenarios ONLY on the explicitly isolated code13 test lab."""
import json
import math
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import otel_lab as lab

HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def main():
    env = lab.environment()
    assert env['LAB_PROJECT_NAME'].startswith('ahwotel-code13-test'), 'isolated_project_required'
    assert lab.LAB != lab.ROOT / 'deploy/otel-lab', 'isolated_configuration_required'
    evidence = {'scenarios': []}

    def cli(*args):
        subprocess.run([str(lab.ROOT / 'scripts/otel-lab.sh'), *args], check=True)

    def await_level(level, token=None):
        deadline = time.monotonic() + 20
        while True:
            states = {name: json.loads(lab.compose(lab.environment(), 'exec', '-T', name,
                      '/lab-supervisor', '--status', capture=True)) for name in ('collector', 'prometheus', 'grafana')}
            if all(s['effective'] == level and (token is None or s['token'] == token) for s in states.values()):
                return states
            assert time.monotonic() < deadline, states
            time.sleep(0.5)

    path = lab.LAB / '.env'
    baseline = lab.read_env(path)
    baseline.update(LAB_BIND_ADDRESS='127.0.0.1', OTLP_PORT='14318', GRAFANA_PORT='14000',
                    PROMETHEUS_PORT='19090', COLLECTOR_HEALTH_PORT='23133', RETENTION_DAYS='14')
    path.write_text(''.join(f'{key}={value}\n' for key, value in baseline.items()))
    cli('up')
    cli('debug', 'Basic')
    await_level('Basic')
    cli('debug', 'Verbose', '--minutes', '1')
    initial = await_level('Verbose')
    # Change real deployment settings while the lease is active.
    path = lab.LAB / '.env'
    desired = lab.read_env(path)
    desired.update(LAB_BIND_ADDRESS='127.0.0.2', OTLP_PORT='14319', GRAFANA_PORT='14001',
                   PROMETHEUS_PORT='19091', COLLECTOR_HEALTH_PORT='23134', RETENTION_DAYS='30')
    path.write_text(''.join(f'{key}={value}\n' for key, value in desired.items()))
    cli('up')
    expires = max(s['expires'] for s in initial.values())
    while time.time() <= expires: time.sleep(0.5)
    states = await_level('Off')
    assert time.time() - expires <= 15, states
    env = lab.environment()
    inspected = json.loads(subprocess.check_output(['docker', 'inspect', env['LAB_PROJECT_NAME'] + '-prometheus-1'], text=True))[0]
    assert '--storage.tsdb.retention.time=30d' in inspected['Config']['Cmd']
    assert inspected['HostConfig']['PortBindings']['9090/tcp'][0]['HostPort'] == '19091'
    lab.health(env)
    evidence['scenarios'].append('expiry preserves new bind/ports/retention and applies Off within 15s')

    # Simulate power loss: no host timer survives; expired policy is evaluated at container startup.
    policy = lab.write_policy('Verbose', 0.25)
    await_level('Verbose', policy['token'])
    lab.compose(env, 'stop')
    while time.time() <= policy['expires']: time.sleep(0.5)
    lab.compose(env, 'start')
    await_level('Off', policy['token'])
    lab.health(env)
    evidence['scenarios'].append('expired lease stays Off after all containers restart')

    # A child crash must terminate PID 1 so Docker recovers it and re-evaluates policy.
    policy = lab.write_policy('Verbose', 1)
    before = await_level('Verbose', policy['token'])
    name = env['LAB_PROJECT_NAME'] + '-grafana-1'
    restarts = lambda: int(subprocess.check_output(['docker', 'inspect', name, '--format', '{{.RestartCount}}'], text=True))
    count = restarts()
    lab.compose(env, 'exec', '-T', 'grafana', '/bin/sh', '-c', 'kill -KILL "$1"', 'sh', str(before['grafana']['child_pid']))
    deadline = time.monotonic() + 30
    while restarts() <= count:
        assert time.monotonic() < deadline, 'supervisor_not_restarted'
        time.sleep(0.5)
    lab.health(env)
    await_level('Verbose', policy['token'])
    lab.POLICY.write_text('{malformed')
    await_level('Off')
    cli('debug', 'Off')
    evidence['scenarios'].append('child crash recovers via Docker; malformed policy becomes Off')

    # Prometheus boundary and provider checks through Collector; no changes to main telemetry.
    device = 'code13-' + str(int(time.time()))
    t = math.floor(time.time()) - 120 + 0.25
    def attrs(values):
        return [{'key': k, 'value': {'stringValue': v}} for k, v in values.items()]
    def point(source, state, stamp):
        return {'timeUnixNano': str(round(stamp * 1000) * 1_000_000), 'asDouble': 1, 'attributes': attrs({
            'metric': 'device.security.screen_lock', 'source': source, 'status': state,
            'reason': 'NONE' if state == 'AVAILABLE' else 'ROLE_REQUIRED', 'measurement.scope': 'device'})}
    payload = {'resourceMetrics': [{'resource': {'attributes': attrs({'device.id': device, 'monitoring.session.id': device})},
        'scopeMetrics': [{'scope': {'name': 'com.ahwotel.oem'}, 'metrics': [{'name': 'agent.oem.capability',
        'gauge': {'dataPoints': [point('ANDROID_STANDARD', 'AVAILABLE', t), point('OEM', 'PERMISSION_DENIED', t + 0.5)]}}]}]}]}
    endpoint = f"http://{env['LAB_BIND_ADDRESS']}:{env['OTLP_PORT']}/v1/metrics"
    with HTTP.open(urllib.request.Request(endpoint, json.dumps(payload).encode(), {'Content-Type': 'application/json'}), timeout=10) as response:
        assert response.status == 200
    prom = f"http://127.0.0.1:{env['PROMETHEUS_PORT']}"
    def query(expr, at):
        with HTTP.open(prom + '/api/v1/query?' + urllib.parse.urlencode({'query': expr, 'time': at}), timeout=10) as response:
            result = json.load(response)
            assert result['status'] == 'success', result
            return result['data']['result']
    expr = ('1000 * topk by (device_id,monitoring_session_id,metric,component,source,measurement_scope) '
            '(1, ts_of_last_over_time(agent_oem_capability{device_id="' + device + '"}[60s]))')
    deadline = time.monotonic() + 30
    while len(query(expr, t + 1)) != 2:
        assert time.monotonic() < deadline, 'fixture_not_ingested'
        time.sleep(0.5)
    assert {r['metric']['source'] for r in query(expr, t + 1)} == {'ANDROID_STANDARD', 'OEM'}
    only_standard = expr.replace('device_id="' + device + '"', 'device_id="' + device + '",source="ANDROID_STANDARD"')
    assert float(query(only_standard, t)[0]['value'][1]) == t * 1000, 'right boundary excluded'
    assert float(query(only_standard, t + 1)[0]['value'][1]) == t * 1000, 'fresh point lost'
    assert query(only_standard, t + 60) == [], 'left boundary included'
    assert query(expr, t + 61) == [], 'old point leaked from lookback'
    evidence.update(passed=True, device=device, time=t, sources=query(expr, t + 1))
    evidence['scenarios'].append('exact range boundaries, latest incomplete minute, independent Android/Knox states')
    out = lab.ROOT / 'artifacts/code13/lab-regression.json'
    out.write_text(json.dumps(evidence, indent=2) + '\n')
    print('PASS: code13 isolated runtime and PromQL regression scenarios')


if __name__ == '__main__': main()
