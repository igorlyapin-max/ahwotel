#!/usr/bin/env python3
"""External ADB observer for the isolated acceptance APK on the Samsung SM-J260F API 27 test device.

Precondition: choose continuous collection and Basic diagnostics in the acceptance app.
No instrumentation runs during the observation, and the working com.ahwotel is never targeted.
"""
import argparse
import io
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tarfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.ahwotel.acceptance"
COMPONENT = PACKAGE + "/com.ahwotel.MainActivity"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("action", choices=("home", "back", "recents"))
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial):
        parser.error("invalid_serial")
    folder = args.evidence
    folder.mkdir(parents=True, exist_ok=False)
    adb = [str(ROOT / "scripts/adb.sh"), "-s", args.serial]

    def call(*parts):
        return subprocess.check_output(adb + list(parts), timeout=45)

    def shell(*parts):
        return call("shell", *parts).decode().strip()

    def shot(name):
        (folder / (name + ".png")).write_bytes(call("exec-out", "screencap", "-p"))

    def snapshot(name):
        path = folder / name
        path.mkdir()
        raw = call("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "databases", "files/datastore")
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            archive.extractall(path, filter="data")
        with sqlite3.connect(path / "databases/monitor.db") as db:
            db.row_factory = sqlite3.Row
            rows = [dict(r) for r in db.execute("SELECT id,status,continuous,startedAt,endedAt,endReason FROM sessions ORDER BY startedAt DESC")]
            counts = dict(db.execute("SELECT sessionId,count(*) FROM samples GROUP BY sessionId"))
            elapsed = dict(db.execute("SELECT sessionId,max(elapsed) FROM samples GROUP BY sessionId"))
        raw = (path / "files/datastore/settings.preferences_pb").read_bytes()
        config = json.loads(raw[raw.index(b"{"):raw.rindex(b"}") + 1])
        return rows, counts, elapsed, config

    def ui():
        shell("uiautomator", "dump", "/sdcard/ahwotel-background.xml")
        raw = call("exec-out", "cat", "/sdcard/ahwotel-background.xml")
        (folder / "last-ui.xml").write_bytes(raw)
        return ET.fromstring(raw)

    def bounds(node):
        return [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]

    def tap_text(text):
        nodes = [n for n in ui().iter("node") if n.get("text") == text and len(bounds(n)) == 4]
        if not nodes:
            raise AssertionError("missing_ui_text: " + text)
        x1, y1, x2, y2 = bounds(nodes[-1])
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tasks():
        output = shell("dumpsys", "activity", "activities")
        return set(re.findall(r"TaskRecord\{[^}\n]* #(\d+) [^}\n]*\bcom\.ahwotel\.acceptance(?:[ /}]|$)", output))

    def service(name):
        output = shell("dumpsys", "activity", "services", PACKAGE)
        (folder / (name + "-services.txt")).write_text(output)
        assert "com.ahwotel.MonitoringService" in output and "isForeground=true" in output, "foreground_service_missing"
        notifications = shell("dumpsys", "notification", "--noredact")
        # Keep only the test app's notification record, not unrelated device notifications.
        own = [line for line in notifications.splitlines() if "NotificationRecord(" in line
               and "pkg=" + PACKAGE + " " in line and "id=42 " in line]
        assert own, "monitoring_notification_missing"
        (folder / (name + "-notification.txt")).write_text("\n".join(own))

    def wait_until(predicate, seconds=10):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.25)
        raise AssertionError("condition_timeout")

    assert shell("getprop", "ro.build.version.sdk") == "27" and shell("getprop", "ro.product.model") == "SM-J260F", "scenario_requires_SM_J260F_API_27_recents_layout"
    rows, _, _, config = snapshot("before")
    assert not any(r["status"] == "RUNNING" for r in rows), "acceptance_session_already_running"
    assert config["continuous"] and config["diagnostic"] == "BASIC", "configure_continuous_and_basic_in_acceptance_UI"
    language = config["language"]
    start_text, stop_text, monitor_text = ("Start monitoring", "Stop", "Monitor") if language == "en" else ("Начать сбор", "Остановить", "Мониторинг")
    original_stay = shell("settings", "get", "global", "stay_on_while_plugged_in")
    session_id = None
    try:
        shell("svc", "power", "stayon", "usb")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("input", "swipe", "270", "800", "270", "200", "350")
        shell("am", "start", "-W", "-n", COMPONENT)
        tap_text(monitor_text)
        tap_text(start_text)
        time.sleep(3)
        rows, counts, elapsed, _ = snapshot("started")
        active = [r for r in rows if r["status"] == "RUNNING"]
        assert len(active) == 1 and active[0]["continuous"] == 1, "continuous_session_not_started"
        session_id = active[0]["id"]
        before_count = counts.get(session_id, 0)
        before_elapsed = elapsed[session_id]
        pid = shell("pidof", PACKAGE)
        assert pid, "process_missing"
        original_tasks = tasks()
        assert original_tasks, "activity_task_missing"
        service("started")
        shot("before-exit")
        if args.action in ("home", "back"):
            shell("input", "keyevent", "KEYCODE_HOME" if args.action == "home" else "KEYCODE_BACK")
        else:
            shell("input", "keyevent", "KEYCODE_APP_SWITCH")
            time.sleep(1)
            shot("recents-before-swipe")
            # Samsung Experience API 27 uses a vertical card stack with horizontal dismissal.
            cards = [n for n in ui().iter("node") if n.get("text") in ("AHWOTel Test", "AHWOTel Тест")]
            assert len(cards) == 1, "unique_acceptance_card_required"
            x1, y1, x2, y2 = bounds(cards[0])
            # Drag the preview: the title bar handles app/window controls separately.
            y = str(min(y2 + 70, 790))
            shell("input", "swipe", "100", y, "535", y, "300")
            shot("recents-after-swipe")
            wait_until(lambda: not (tasks() & original_tasks))
        activity = shell("dumpsys", "activity", "activities")
        focused = [s for s in activity.splitlines() if "mResumedActivity" in s or "ResumedActivity:" in s]
        assert not any(PACKAGE in s for s in focused), "app_still_foreground"
        (folder / "after-exit-activity.txt").write_text("\n".join(focused))
        shot("after-exit")
        for second in range(3):
            time.sleep(10)
            assert shell("pidof", PACKAGE) == pid, "process_terminated_after_exit"
            print(f"{args.action}: observing {(second + 1) * 10}s", flush=True)
        rows, counts, elapsed, _ = snapshot("background")
        active = [r for r in rows if r["status"] == "RUNNING"]
        assert len(active) == 1 and active[0]["id"] == session_id, "background_session_changed"
        assert counts[session_id] > before_count, "no_background_samples"
        assert elapsed[session_id] - before_elapsed >= 30_000, "less_than_30_seconds_of_sampling"
        background_count = counts[session_id]
        service("background")
        shell("am", "start", "-W", "-n", COMPONENT)
        tap_text(monitor_text)
        rows, _, _, _ = snapshot("reopened")
        assert any(r["id"] == session_id and r["status"] == "RUNNING" for r in rows), "reopen_changed_session"
        shot("reopened")
        shell("cmd", "statusbar", "expand-notifications")
        time.sleep(1)
        tree = ui()
        stop_nodes = [n for n in tree.iter("node") if n.get("text", "").lower() == stop_text.lower()]
        if not stop_nodes:
            # Expand the ongoing notification to expose its Stop action on this Samsung.
            shell("input", "swipe", "250", "170", "250", "650", "400")
        tap_text(stop_text.upper() if any(n.get("text") == stop_text.upper() for n in ui().iter("node")) else stop_text)
        time.sleep(3)
        rows, counts, _, _ = snapshot("stopped")
        ended = next(r for r in rows if r["id"] == session_id)
        assert ended["status"] == "FINISHED" and ended["endReason"] == "manual_stop", "notification_stop_failed"
        stopped_count = counts.get(session_id, 0)
        time.sleep(3)
        _, counts, _, _ = snapshot("after-stop")
        assert counts.get(session_id, 0) == stopped_count, "samples_after_stop"
        result = {"action": args.action, "package": PACKAGE, "sameSession": True, "sameProcess": True,
                  "samplesBeforeExit": before_count, "samplesAfterExit": background_count,
                  "notificationStop": True, "noSamplesAfterStop": True, "observationSeconds": 30}
        (folder / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result), flush=True)
    finally:
        # Best effort UI cleanup, even if the device killed the process. Never modify working data.
        if session_id:
            try:
                shell("cmd", "statusbar", "collapse")
                shell("am", "start", "-W", "-n", COMPONENT)
                tap_text(monitor_text)
                if any(n.get("text") == stop_text for n in ui().iter("node")):
                    tap_text(stop_text)
            except (AssertionError, subprocess.SubprocessError):
                print("cleanup_requires_inspection_of_acceptance_app", flush=True)
        shell("settings", "put", "global", "stay_on_while_plugged_in", original_stay)


if __name__ == "__main__":
    main()
