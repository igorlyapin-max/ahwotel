#!/usr/bin/env python3
"""Stage only the pinned public SDK stub; never read license material."""
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT/'.tools/knox/Knox-SDK-3.13-(API-level-40).zip'

def main():
    expected = (ROOT/'scripts/knox-sdk.sha256').read_text().split()[0]
    if not ARCHIVE.is_file(): raise SystemExit('Download Knox SDK 3.13 / API 40 to .tools/knox; see docs/knox-customer-guide-en.md')
    if hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() != expected: raise SystemExit('knox_sdk_checksum_mismatch')
    with zipfile.ZipFile(ARCHIVE) as archive:
        names = [n for n in archive.namelist() if n.endswith('/libs/knoxsdk.jar')]
        if len(names) != 1: raise SystemExit('knox_sdk_layout_invalid')
        data = archive.read(names[0])
    target = ROOT/'app/build/knox/knoxsdk.jar'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print('Knox SDK 3.13 / API 40 compile-only stub prepared')

if __name__ == '__main__': main()
