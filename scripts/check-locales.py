#!/usr/bin/env python3
"""Fail on missing translations or incompatible Android format placeholders."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

base = Path(__file__).resolve().parents[1] / 'app/src/main/res'
def read(folder):
    result = {}
    for file in (base / folder).glob('*.xml'):
        for item in ET.parse(file).getroot():
            if item.tag != 'string':
                continue
            name = item.attrib['name']
            assert name not in result, f'Duplicate resource: {folder}/{name}'
            result[name] = ''.join(item.itertext())
    return result
english, russian = read('values'), read('values-ru')
assert english.keys() == russian.keys(), f'Locale key mismatch: {english.keys() ^ russian.keys()}'
for name in english:
    assert re.findall(r'%\d+\$[dsf]', english[name]) == re.findall(r'%\d+\$[dsf]', russian[name]), name
source = (base.parent / 'java/com/ahwotel/Database.kt').read_text()
metrics = re.findall(r'^\s+([A-Z][A-Z_]+)\("', source, re.M)
sections = ('meaning', 'units', 'impact', 'reading', 'example', 'limits', 'state', 'source')
expected = {f'help_{metric.lower()}_{section}' for metric in metrics for section in sections}
oem_source = (base.parent / 'java/com/ahwotel/oem/OemModel.kt').read_text()
oem_metrics = re.findall(r'^\s+([A-Z][A-Z_]+)\("', oem_source, re.M)
expected |= {f'oem_{metric.lower()}_{section}' for metric in oem_metrics for section in sections}
expected |= {f'oem_{metric.lower()}_title' for metric in oem_metrics}
for locale in (english, russian):
    for name in expected:
        assert name in locale and locale[name].strip(), f'Missing help section: {name}'
assert len(metrics) == len(set(metrics)) and metrics
references = (base.parent / 'java/com/ahwotel/OemTextResources.kt').read_text()
assert {k for k in english if k.startswith('oem_')} == set(re.findall(r'R\.string\.(oem_[a-z_]+)', references)), 'Run scripts/update-oem-resources.py'
agent_source = (base.parent / 'java/com/ahwotel/AgentMetric.kt').read_text()
agent_metrics = re.findall(r'^\s+([A-Z][A-Z_]+)\("', agent_source, re.M)
agent_wires = dict(re.findall(r'^\s+([A-Z][A-Z_]+)\("([^"]+)"', agent_source, re.M))
for name in agent_metrics:
    for locale in (english, russian):
        assert locale.get(f'at_{name.lower()}_title')
        help_text = locale.get(f'at_{name.lower()}_help', '')
        assert '\\n' + agent_wires[name] + '.' in help_text, f'Incorrect wire ID in help: {name}'
        assert help_text.count('\\n\\n') == 7, f'Expected eight help sections: {name}'
# Known prose regressions in the new battery/Self surface. Technical wire/API IDs remain verbatim.
for name, value in russian.items():
    if name.startswith('at_'):
        assert not re.search(r'(?<![A-Za-z_.])(baseline|payload|Stop|runtime|heap|release|timeout)(?![A-Za-z_.])', value), f'Untranslated prose: {name}'
print(f'PASS: {len(english)} EN/RU strings, matching placeholders; {len(metrics)} base + {len(oem_metrics)} OEM metrics × {len(sections)} help sections per language')
