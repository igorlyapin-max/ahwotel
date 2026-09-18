"""Failure and lifecycle observations without touching the running lab."""
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import otel_lab as lab
import otel_lab_soak as soak

ENV = {'PROMETHEUS_PORT': '9090', 'GRAFANA_PORT': '3000', 'LAB_BIND_ADDRESS': '127.0.0.1'}


class Reply(io.BytesIO):
    status = 200


class Http:
    def open(self, url, timeout):
        return Reply(json.dumps({'data': {'result': [{'value': [1000, '999']}]}}).encode())


def healthy():
    return {'containers': {name: {'container_id': name, 'started_at': 'start', 'image_id': 'image',
             'restarts': 0, 'child_generation': 1, 'state': 'running'}
             for name in ('collector', 'prometheus', 'grafana')},
            'queue': {'size': 0, 'capacity': 10000, 'full': False}, 'errors': []}


class SoakTest(unittest.TestCase):
    def observe(self, runtime, baseline=None):
        with patch.object(soak, 'runtime_status', return_value=runtime), patch.object(soak.time, 'time', return_value=1000):
            return soak.sample(ENV, Http(), 'test-device', baseline)

    def test_all_identity_changes_fail_even_with_same_image(self):
        _, baseline = self.observe(healthy())
        for field, value in [('container_id', 'new'), ('started_at', 'new-start'), ('image_id', 'new-image'),
                             ('restarts', 1), ('child_generation', 2)]:
            with self.subTest(field=field):
                runtime = healthy(); runtime['containers']['collector'][field] = value
                row, _ = self.observe(runtime, baseline)
                self.assertIn('unexpected_runtime_change', row['errors'])

    def test_healthy_identity_is_stable(self):
        first, baseline = self.observe(healthy())
        second, after = self.observe(healthy(), baseline)
        self.assertEqual([], first['errors']); self.assertEqual([], second['errors'])
        self.assertEqual(baseline, after)

    def test_errors_keep_runtime_and_specific_diagnostics(self):
        for error in ('collector_queue_full', 'prometheus_not_running', 'grafana_memory_unavailable'):
            runtime = healthy(); runtime['errors'] = [error]
            if error == 'collector_queue_full': runtime['queue']['size'] = 10000
            row, baseline = self.observe(runtime)
            self.assertIsNone(baseline)
            self.assertEqual([error], row['errors'])
            self.assertEqual(runtime, row['runtime'])
            self.assertEqual(1, row['sample_age_seconds'])

    def test_failed_sample_prevents_final_pass_after_recovery(self):
        bad = healthy(); bad['errors'] = ['collector_queue_full']; bad['queue']['size'] = 10000
        with tempfile.TemporaryDirectory() as out, patch('sys.argv', ['soak', '--hours', '.0001', '--device-id', 'test', '--output', out]), \
             patch.object(soak, 'environment', return_value=ENV), patch.object(soak, 'runtime_status', side_effect=[bad, healthy()]), \
             patch.object(soak.urllib.request, 'build_opener', return_value=Http()), \
             patch.object(soak.time, 'time', return_value=1000), patch.object(soak.time, 'monotonic', side_effect=[0, 0, 0, 1]), \
             patch.object(soak.time, 'sleep'):
            with self.assertRaises(SystemExit) as exit_: soak.main()
            self.assertEqual(1, exit_.exception.code)
            result = json.loads((Path(out) / 'result.json').read_text())
            rows = [json.loads(x) for x in (Path(out) / 'samples.jsonl').read_text().splitlines()]
            self.assertEqual('FAIL', result['state']); self.assertEqual(1, result['failed_samples'])
            self.assertEqual(10000, rows[0]['runtime']['queue']['size'])
            self.assertEqual(['collector_queue_full'], rows[0]['errors'])

    def test_runtime_keeps_inspect_result_when_stats_fails(self):
        def compose(env, *args, **kwargs):
            if args[0] == 'ps': return args[-1]
            if args[-1] == '--status': return json.dumps({'child_generation': 4, 'child_pid': 12})
            return ('otelcol_exporter_queue_size{exporter="otlp_http/prometheus"} 0\n'
                    'otelcol_exporter_queue_capacity{exporter="otlp_http/prometheus"} 10000\n')
        def output(args, **kwargs):
            if args[1] == 'stats': raise TimeoutError('must not enter diagnostic output')
            return json.dumps([{'Id': args[-1], 'Image': 'image', 'RestartCount': 0,
                'State': {'Status': 'exited', 'Running': False, 'StartedAt': 'start'}, 'HostConfig': {'Memory': 1024}}])
        with patch.object(lab, 'compose', side_effect=compose), patch.object(lab.subprocess, 'check_output', side_effect=output):
            result = lab.runtime_status({})
        self.assertEqual('prometheus', result['containers']['prometheus']['container_id'])
        self.assertEqual(4, result['containers']['prometheus']['child_generation'])
        self.assertIn('prometheus_not_running', result['errors'])
        self.assertIn('prometheus_memory_unavailable', result['errors'])

    def test_cli_status_still_exits_nonzero_and_prints_evidence(self):
        broken = healthy(); broken['errors'] = ['collector_queue_full']
        with patch('sys.argv', ['lab', 'status']), patch.object(lab, 'environment', return_value={}), \
             patch.object(lab, 'compose'), patch.object(lab, 'runtime_status', return_value=broken), \
             patch('sys.stdout', new_callable=io.StringIO) as output:
            with self.assertRaisesRegex(RuntimeError, 'collector_queue_full'): lab.main()
            self.assertEqual(broken, json.loads(output.getvalue()))


if __name__ == '__main__': unittest.main()
