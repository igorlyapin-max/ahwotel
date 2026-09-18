#!/usr/bin/env python3
"""Reproducible local OTLP lab. No automatic IP selection or data deletion."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import uuid
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LAB = Path(os.environ.get('AHWOTEL_LAB_DIR', ROOT / 'deploy/otel-lab')).resolve()
STATE = LAB / '.runtime'
POLICY = STATE / 'policy' / 'debug.json'


def read_env(path):
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            raise ValueError('invalid_environment_key')
        values[key] = value
    return values


def environment():
    values = read_env(LAB / '.env.example')
    if (LAB / '.env').exists():
        values.update(read_env(LAB / '.env'))
    values.update({k: os.environ[k] for k in values if k in os.environ})
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', values['LAB_PROJECT_NAME']):
        raise ValueError('invalid_LAB_PROJECT_NAME')
    if not (LAB / '.env').exists() and 'LAB_BIND_ADDRESS' not in os.environ:
        raise ValueError('Set LAB_BIND_ADDRESS or copy deploy/otel-lab/.env.example to .env and edit it')
    address = ipaddress.ip_address(values['LAB_BIND_ADDRESS'])
    if address.version != 4 or address.is_unspecified or address.is_multicast:
        raise ValueError('LAB_BIND_ADDRESS must be an explicit host IPv4 address')
    for name in ['OTLP_PORT', 'GRAFANA_PORT', 'PROMETHEUS_PORT', 'COLLECTOR_HEALTH_PORT']:
        if not 1 <= int(values[name]) <= 65535:
            raise ValueError('invalid_port: ' + name)
    if not 1 <= int(values['RETENTION_DAYS']) <= 90:
        raise ValueError('RETENTION_DAYS must be 1..90')
    if not re.fullmatch(r'[1-9][0-9]*(MB|GB)', values['RETENTION_SIZE']):
        raise ValueError('invalid_RETENTION_SIZE')
    for name in ('PROMETHEUS_MEMORY_LIMIT', 'GRAFANA_MEMORY_LIMIT'):
        if not re.fullmatch(r'[1-9][0-9]*(m|g)', values[name]):
            raise ValueError('invalid_' + name)
    if not re.fullmatch(r'[1-9][0-9]*(m|h|d)', values['OUT_OF_ORDER_WINDOW']):
        raise ValueError('invalid_OUT_OF_ORDER_WINDOW')
    if values['LAB_SNAP_DOCKER'] not in ('0','1'):
        raise ValueError('invalid_LAB_SNAP_DOCKER')
    return {**os.environ, **values}


def compose(env, *args, capture=False, timeout=None):
    cmd = ['docker', 'compose', '--project-directory', str(LAB), '--env-file', str(LAB / 'images.env'),
           '-f', str(LAB / 'compose.yaml'), '-f', str(LAB / 'compose.local-logs.yaml')]
    if env.get('LAB_SNAP_DOCKER') == '1':
        cmd += ['-f', str(LAB / 'compose.snap-docker.yaml')]
    cmd += list(args)
    return subprocess.run(cmd, env=env, text=True, check=True, timeout=timeout,
                          stdout=subprocess.PIPE if capture else None).stdout


def prepare(env):
    STATE.mkdir(exist_ok=True)
    template = (LAB / 'prometheus.yaml.template').read_text()
    (STATE / 'prometheus.yaml').write_text(template.replace('${OUT_OF_ORDER_WINDOW}', env['OUT_OF_ORDER_WINDOW']))
    if not POLICY.exists():
        write_policy('Off') # First supervisor deployment starts Off; legacy host timer state is not reused.
    compose(env, 'config', '--quiet')


def write_policy(level, minutes=10):
    POLICY.parent.mkdir(parents=True, exist_ok=True)
    now = time.time()
    policy = {'version': 1, 'level': level, 'token': str(uuid.uuid4()), 'issued': now,
              'expires': now + minutes * 60 if level == 'Verbose' else 0}
    with tempfile.NamedTemporaryFile('w', dir=POLICY.parent, delete=False) as file:
        json.dump(policy, file)
        file.flush()
        os.fsync(file.fileno())
        os.fchmod(file.fileno(), 0o644) # Readable by all three non-root container identities; contains no secrets.
        temporary = file.name
    os.replace(temporary, POLICY)
    return policy


def diagnostic_status(env, expected=None):
    results = {}
    for service in ('collector', 'prometheus', 'grafana'):
        deadline = time.monotonic() + (20 if expected else 0)
        while True:
            raw = compose(env, 'exec', '-T', service, '/lab-supervisor', '--status', capture=True)
            current = json.loads(raw)
            target = expected['level'] if expected else None
            if expected and target == 'Verbose' and time.time() >= expected['expires']:
                target = 'Off'
            if not expected or (current['token'] == expected['token'] and current['effective'] == target):
                results[service] = current
                break
            if time.monotonic() >= deadline:
                raise RuntimeError(service + '_diagnostic_not_applied')
            time.sleep(0.5)
    print(json.dumps({'diagnostics': results}))
    return results


def health(env, wait=True):
    urls = {'collector': f"http://127.0.0.1:{env['COLLECTOR_HEALTH_PORT']}/",
            'prometheus': f"http://127.0.0.1:{env['PROMETHEUS_PORT']}/-/ready",
            'grafana': f"http://{env['LAB_BIND_ADDRESS']}:{env['GRAFANA_PORT']}/api/health"}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for name, url in urls.items():
        deadline = time.monotonic() + (180 if wait else 0)
        while True:
            try:
                with opener.open(url, timeout=3) as response:
                    assert response.status == 200
                    print(name + ': ready')
                    break
            except Exception:
                if time.monotonic() >= deadline:
                    raise RuntimeError(name + '_not_ready')
                time.sleep(1)


def queue_status(text):
    metrics = {}
    for line in text.splitlines():
        if line.startswith('otelcol_exporter_queue_') and 'exporter="otlp_http/prometheus"' in line:
            name, value = line.split('{', 1)[0], float(line.rsplit(' ', 1)[1])
            metrics[name] = value
    size = metrics['otelcol_exporter_queue_size']
    capacity = metrics['otelcol_exporter_queue_capacity']
    if capacity <= 0 or size < 0:
        raise ValueError('invalid_collector_queue_metrics')
    return {'size': size, 'capacity': capacity, 'full': size >= capacity}


def runtime_status(env):
    result = {'containers': {}, 'queue': None, 'errors': []}
    for service in ('collector', 'prometheus', 'grafana'):
        try:
            identifier = compose(env, 'ps', '-aq', service, capture=True, timeout=15).strip()
            info = json.loads(subprocess.check_output(['docker', 'inspect', identifier], timeout=15))[0]
            current = {'container_id': info['Id'], 'started_at': info['State']['StartedAt'],
                'state': info['State']['Status'], 'restarts': info['RestartCount'],
                'memory_limit_bytes': info['HostConfig']['Memory'], 'image_id': info['Image']}
            result['containers'][service] = current
            if not info['State']['Running']:
                result['errors'].append(service + '_not_running')
            try:
                current['memory_usage'] = subprocess.check_output(['docker', 'stats', '--no-stream', '--format', '{{.MemUsage}}', identifier], text=True, timeout=15).strip()
            except Exception:
                result['errors'].append(service + '_memory_unavailable')
            try:
                child = json.loads(compose(env, 'exec', '-T', service, '/lab-supervisor', '--status', capture=True, timeout=15))
                current['child_generation'] = child['child_generation']
                current['child_pid'] = child['child_pid']
                if not isinstance(child['child_generation'], int) or child['child_generation'] < 1 or child['child_pid'] <= 0:
                    raise ValueError('invalid_child_identity')
            except Exception:
                result['errors'].append(service + '_child_unavailable')
        except Exception:
            result['errors'].append(service + '_status_unavailable')
    try:
        raw = compose(env, 'exec', '-T', 'grafana', 'wget', '-T', '5', '-q', '-O', '-', 'http://collector:8888/metrics', capture=True, timeout=15)
        result['queue'] = queue_status(raw)
        if result['queue']['full']:
            result['errors'].append('collector_queue_full')
    except Exception:
        result['errors'].append('collector_queue_unavailable')
    # The caller decides exit status; failures retain every available structured field.
    return result


def addresses(env):
    host = env['LAB_BIND_ADDRESS']
    print(f"APK endpoint: http://{host}:{env['OTLP_PORT']}/v1/metrics")
    for lang in ('en', 'ru'):
        print(f"Grafana {lang}: http://{host}:{env['GRAFANA_PORT']}/d/ahwotel-{lang}")
    print('Authentication: None; enable Allow HTTP for a test lab in the APK.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['up', 'down', 'status', 'logs', 'smoke', 'debug'])
    parser.add_argument('level', nargs='?', choices=['Off', 'Basic', 'Verbose'])
    parser.add_argument('--minutes', type=int, default=10)
    parser.add_argument('--restart-queue', action='store_true', help='Smoke: interrupt only the lab to verify durable delivery')
    args = parser.parse_args()
    env = environment()
    if args.command == 'up':
        prepare(env)
        images = read_env(LAB / 'images.env')
        queue_volume = env['LAB_PROJECT_NAME'] + '-collector-queue'
        subprocess.run(['docker', 'volume', 'create', queue_volume], check=True, stdout=subprocess.DEVNULL)
        # One-shot volume ownership initialization. All long-running services are non-root.
        subprocess.run(['docker', 'run', '--rm', '--user', '0:0', '--entrypoint', '/bin/sh',
                        '-v', queue_volume + ':/queue', images['GRAFANA_IMAGE'],
                        '-c', 'chown 10001:10001 /queue'], check=True)
        # Bind-mounted config content is not part of Compose's recreate hash.
        compose(env, 'up', '-d', '--build', '--force-recreate', '--remove-orphans')
        health(env)
        diagnostic_status(env)
        addresses(env)
    elif args.command == 'down':
        compose(env, 'down')
        print('Named volumes retained.')
    elif args.command == 'status':
        compose(env, 'ps')
        result = runtime_status(env)
        print(json.dumps(result))
        if result['errors']:
            raise RuntimeError(','.join(result['errors']))
        health(env, wait=False)
        diagnostic_status(env)
        addresses(env)
    elif args.command == 'logs':
        compose(env, 'logs', '--tail', '100', '--no-color')
    elif args.command == 'smoke':
        health(env)
        subprocess.run([sys.executable, str(ROOT / 'scripts/otel_lab_smoke.py')] +
                       (['--restart-queue'] if args.restart_queue else []), env=env, check=True)
    elif args.command == 'debug':
        if args.level is None or not 1 <= args.minutes <= 30:
            parser.error('Use debug Off|Basic|Verbose [--minutes 1..30]')
        # Fail before writing a lease if the lab is stopped or still uses code12 images.
        diagnostic_status(env)
        policy = write_policy(args.level, args.minutes)
        diagnostic_status(env, policy)
        health(env)
        print('Diagnostic level: ' + args.level)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
