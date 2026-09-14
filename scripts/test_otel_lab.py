#!/usr/bin/env python3
"""Focused offline checks for the code13 lab contracts."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import otel_lab as lab

spec = importlib.util.spec_from_file_location('dashboards', Path(__file__).with_name('build-lab-dashboards.py'))
dashboards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dashboards)


class LabRegressionTest(unittest.TestCase):
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
