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
for locale in (english, russian):
    for name in expected:
        assert name in locale and locale[name].strip(), f'Missing help section: {name}'
assert len(metrics) == len(set(metrics)) and metrics
print(f'PASS: {len(english)} EN/RU strings, matching placeholders; {len(metrics)} metrics × {len(sections)} help sections per language')
