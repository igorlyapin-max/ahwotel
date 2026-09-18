#!/usr/bin/env python3
"""Destructive lifecycle scenarios only in the isolated acceptance package on an emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.ahwotel.acceptance'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/recovery-code16/lifecycle-api35')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('emulator_required; physical device reboot is tested separately')
    adb = [str(ROOT / 'scripts/adb.sh'), '-s', args.serial]
    out = args.output
    out.mkdir(parents=True, exist_ok=True)

    def call(*a, timeout=90):
        return subprocess.check_output(adb + list(a), timeout=timeout)

    def shell(*a):
        return call('shell', *a).decode().strip()

    def wait(predicate, seconds=90):
        until = time.monotonic() + seconds
        while not predicate():
            if time.monotonic() >= until:
                raise AssertionError('lifecycle_timeout')
            time.sleep(1)

    def active():
        return bool(pid()) and 'isForeground=true' in shell('dumpsys', 'activity', 'services', PACKAGE)

    def pid():
        return subprocess.run(adb + ['shell', 'pidof', PACKAGE], capture_output=True, text=True, timeout=15).stdout.strip()

    def opened():
        shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        shell('wm', 'dismiss-keyguard')
        shell('am', 'start', '-n', PACKAGE + '/com.ahwotel.MainActivity')

    def nodes():
        shell('uiautomator', 'dump', '/sdcard/ahwotel-resume.xml')
        raw = call('exec-out', 'cat', '/sdcard/ahwotel-resume.xml')
        (out / 'last-ui.xml').write_bytes(raw)
        found = list(ET.fromstring(raw).iter('node'))
        # Known emulator prerequisite: only dismiss a System UI ANR, never an app ANR.
        if any('Интерфейс системы' in n.get('text', '') or 'System UI' in n.get('text', '') for n in found):
            for n in found:
                if n.get('text') in ('Закрыть приложение', 'Close app'):
                    (out / 'system-ui-anr.png').write_bytes(call('exec-out', 'screencap', '-p'))
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
                    shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    print('Recovered emulator System UI ANR dialog', flush=True)
                    return []
        return found

    def tap(text):
        found = []
        until = time.monotonic() + 30
        while not found and time.monotonic() < until:
            found = [n for n in nodes() if n.get('text') == text]
            if not found: time.sleep(1)
        if not found:
            (out / 'failure.png').write_bytes(call('exec-out', 'screencap', '-p'))
            raise AssertionError(text)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', found[-1].get('bounds')))
        shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

    def session():
        until = time.monotonic() + 30
        while time.monotonic() < until:
            for n in nodes():
                t = n.get('text', '')
                if re.fullmatch(r'[0-9a-f-]{36}', t): return t
            time.sleep(1)
        raise AssertionError('session_id_not_visible')

    def var(n):
        b = bytearray()
        while n > 127:
            b.append((n & 127) | 128); n >>= 7
        b.append(n)
        return bytes(b)

    def field(n, b): return var(n*8+2) + var(len(b)) + b

    def prefs(raw):
        shell('am', 'force-stop', PACKAGE)
        p = out / 'settings.preferences_pb'; p.write_bytes(raw)
        subprocess.run(adb + ['push', str(p), '/data/local/tmp/resume-test.pb'], check=True, stdout=subprocess.DEVNULL)
        shell('run-as', PACKAGE, 'cp', '/data/local/tmp/resume-test.pb', 'files/datastore/settings.preferences_pb')

    if active():
        opened(); tap('Stop'); wait(lambda: not active())
    original = call('exec-out', 'run-as', PACKAGE, 'cat', 'files/datastore/settings.preferences_pb')
    (out / 'original.preferences_pb').write_bytes(original)
    cfg, _ = json.JSONDecoder().raw_decode(original[original.index(b'{'):].decode(errors='replace'))
    cfg.update(continuous=True, resumeOnBoot=True, resumeOnOpen=True, language='en', otlpEnabled=False)
    raw = field(1, field(1, b'configuration') + field(2, field(5, json.dumps(cfg).encode())))
    results = {}
    try:
        prefs(raw); opened()
        wait(lambda: any(n.get('text') == 'Monitor' for n in nodes()), 60)
        tap('Monitor')
        wait(lambda: any(n.get('text') == 'Start monitoring' for n in nodes()), 60)
        assert not active(), 'old settings must not arm'
        tap('Start monitoring'); wait(active)
        first = session()
        print('Started first session', first, flush=True)
        shell('input', 'keyevent', 'KEYCODE_HOME')
        previous_pid = pid()
        assert previous_pid.isdigit()
        shell('run-as', PACKAGE, 'kill', '-9', previous_pid)
        wait(lambda: active() and pid() != previous_pid)
        opened(); second = session(); assert second != first
        results['processKill'] = {'before': first, 'after': second}
        print('Process recovery passed', flush=True)
        shell('input', 'keyevent', 'KEYCODE_HOME')
        shell('am', 'force-stop', PACKAGE); time.sleep(3); assert not active()
        opened(); wait(active); third = session(); assert third != second
        results['forceStopThenExplicitOpen'] = {'after': third}
        print('Force-stop then open passed', flush=True)
        shell('input', 'keyevent', 'KEYCODE_HOME')
        call('reboot'); call('wait-for-device', timeout=180)
        wait(lambda: shell('getprop', 'sys.boot_completed') == '1', 180)
        shell('input', 'keyevent', 'KEYCODE_WAKEUP'); shell('wm', 'dismiss-keyguard')
        wait(active, 120) # Must resume before the Activity is opened.
        opened(); fourth = session(); assert fourth != third
        results['boot'] = {'after': fourth}
        print('Boot recovery passed', flush=True)
        tap('Stop'); wait(lambda: not active())
        call('reboot'); call('wait-for-device', timeout=180)
        wait(lambda: shell('getprop', 'sys.boot_completed') == '1', 180)
        opened(); time.sleep(8); assert not active()
        results['stopSurvivesBootAndOpen'] = True
        (out / 'result.json').write_text(json.dumps({'passed': True, **results}, indent=2))
        print(json.dumps(results))
    finally:
        # Restore test-owned settings; force-stop is intentionally not used on the main package.
        try:
            if active():
                opened(); tap('Stop'); wait(lambda: not active())
        finally:
            prefs(original)


if __name__ == '__main__': main()
