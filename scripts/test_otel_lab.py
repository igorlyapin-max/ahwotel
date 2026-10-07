#!/usr/bin/env python3
"""Focused offline checks for the code13 lab contracts."""
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import otel_lab as lab

spec = importlib.util.spec_from_file_location('dashboards', Path(__file__).with_name('build-lab-dashboards.py'))
dashboards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dashboards)


class LabRegressionTest(unittest.TestCase):
    def test_runtime_images_ports_and_datasource_are_explicit(self):
        dockerfile = (lab.LAB / 'supervisor' / 'Dockerfile').read_text()
        compose = (lab.LAB / 'compose.yaml').read_text()
        images = lab.read_env(lab.LAB / 'images.env')
        datasource = (lab.LAB / 'grafana' / 'provisioning' / 'datasources' / 'prometheus.yaml').read_text()

        from_lines = [line for line in dockerfile.splitlines() if line.startswith('FROM ')]
        self.assertEqual(4, len(from_lines))
        self.assertTrue(all('@sha256:' in line for line in from_lines))
        self.assertNotIn('FROM ${', dockerfile)
        for target, runtime_user in (('collector', '10001:10001'), ('prometheus', '65534:65534'), ('grafana', '472:0')):
            self.assertIn('target: ' + target, compose)
            stage = re.search(r' AS ' + target + r'\n(?P<body>.*?)(?=\nFROM |\Z)', dockerfile, re.DOTALL)
            self.assertIsNotNone(stage)
            self.assertIn('USER ' + runtime_user, stage.group('body'))

        grafana_from = next(line for line in from_lines if line.endswith(' AS grafana'))
        self.assertEqual(images['GRAFANA_IMAGE'], grafana_from.removeprefix('FROM ').removesuffix(' AS grafana'))
        self.assertEqual(4, compose.count('host_ip:'))
        self.assertIn('host_ip: "${LAB_BIND_ADDRESS:?Set the LAN address}"', compose)
        self.assertIn('host_ip: 127.0.0.1', compose)
        self.assertIn('url: http://$PROMETHEUS_SERVICE_HOST:$PROMETHEUS_SERVICE_PORT', datasource)
        self.assertIn('PROMETHEUS_SERVICE_HOST: prometheus', compose)
        self.assertIn('PROMETHEUS_SERVICE_PORT: "9090"', compose)

    def test_queue_status_distinguishes_full_and_missing_metrics(self):
        fixture = ('otelcol_exporter_queue_size{exporter="otlp_http/prometheus"} %s\n'
                   'otelcol_exporter_queue_capacity{exporter="otlp_http/prometheus"} 10000\n')
        self.assertTrue(lab.queue_status(fixture % 10000)['full'])
        self.assertFalse(lab.queue_status(fixture % 9999)['full'])
        with self.assertRaises(KeyError): lab.queue_status('')

    def test_memory_limit_validation(self):
        for name in ('PROMETHEUS_MEMORY_LIMIT', 'GRAFANA_MEMORY_LIMIT'):
            for value in ('2g', '1024m', '0g', '2GB', '-1g', 'unlimited'):
                with self.subTest(name=name, value=value), patch.dict('os.environ', {'LAB_BIND_ADDRESS': '127.0.0.1', name: value}):
                    if value in ('2g', '1024m'): self.assertEqual(value, lab.environment()[name])
                    else:
                        with self.assertRaisesRegex(ValueError, 'MEMORY_LIMIT'): lab.environment()

    def test_help_is_exact_localized_and_published(self):
        for lang in ('en', 'ru'):
            board = dashboards.build(lang)
            help_board = dashboards.build(lang, True)
            self.assertEqual(20, len(help_board['panels']))
            for title in (('System CPU, when supported', 'Thermal status') if lang == 'en' else
                          ('CPU системы, если доступен', 'Тепловой статус')):
                panel = next(p for p in help_board['panels'] if p['title'] == title)
                content = panel['options']['content']
                self.assertEqual(8, content.count('### '))
                if 'CPU' in title:
                    self.assertNotIn('Миллисекунды', content)
                    self.assertNotIn('Milliseconds', content)
            for panel in board['panels']:
                if panel['type'] == 'row': continue
                self.assertLess(len(panel['description']), 400)
                self.assertIn('/d/ahwotel-help-' + lang, panel['links'][0]['url'])
            for help_mode, prefix in ((False, 'ahwotel-'), (True, 'ahwotel-help-')):
                published = json.loads((dashboards.OUT / (prefix + lang + '.json')).read_text())
                self.assertEqual(dashboards.build(lang, help_mode), published)

    def test_availability_queries_keep_sources_and_exact_time(self):
        for lang in ('en', 'ru'):
            for panel in dashboards.build(lang)['panels']:
                if panel.get('type') != 'table': continue
                query = panel['targets'][0]['expr']
                if 'capability' not in query and 'availability' not in query: continue
                self.assertIn('source,measurement_scope', query)
                self.assertIn('ts_of_last_over_time(', query)
                self.assertNotIn(':1m', query)
                self.assertNotIn('timestamp(', query)

    def test_debug_command_changes_policy_without_recreating_containers(self):
        with tempfile.TemporaryDirectory() as folder:
            policy = Path(folder) / 'policy' / 'debug.json'
            with patch.object(lab, 'POLICY', policy), patch.object(lab, 'environment', return_value={}), \
                 patch.object(lab, 'compose') as compose, patch.object(lab, 'health'), \
                 patch.object(lab, 'diagnostic_status'), patch('sys.argv', ['otel_lab.py', 'debug', 'Verbose', '--minutes', '1']):
                lab.main()
                compose.assert_not_called()
            value = json.loads(policy.read_text())
            self.assertEqual('Verbose', value['level'])
            self.assertEqual(60, value['expires'] - value['issued'])
            self.assertEqual(1, value['version'])


if __name__ == '__main__': unittest.main()
