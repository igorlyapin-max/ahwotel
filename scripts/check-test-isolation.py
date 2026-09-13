#!/usr/bin/env python3
"""Synthetic preservation check. Only an empty com.ahwotel profile on an emulator may be seeded."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
TABLES = ("samples", "sessions", "outbox")


def varint(n):
    result = bytearray()
    while n > 127:
        result.append((n & 127) | 128)
        n >>= 7
    return bytes(result) + bytes([n])


def field(number, data):
    return varint(number * 8 + 2) + varint(len(data)) + data


def preferences(configuration):
    # androidx.datastore.preferences PreferencesMap -> map entry -> Value.string.
    return field(1, field(1, b"configuration") + field(2, field(5, configuration.encode())))


def digest(folder):
    with sqlite3.connect(folder / "monitor.db") as db:
        result = {}
        for table in TABLES:
            rows = db.execute(f"select * from {table} order by id").fetchall()
            result[table] = {"count": len(rows), "sha256": hashlib.sha256(repr(rows).encode()).hexdigest()}
        result["roomVersion"] = db.execute("pragma user_version").fetchone()[0]
    result["settings"] = hashlib.sha256((folder / "settings.preferences_pb").read_bytes()).hexdigest()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("stage", choices=("prepare", "verify"))
    parser.add_argument("--evidence", type=Path, default=ROOT / "artifacts/v0.2.2/isolation")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("emulator_required; physical devices are forbidden")
    adb = [str(ROOT / "scripts/adb.sh"), "-s", args.serial]

    def call(*command):
        return subprocess.check_output(adb + list(command), timeout=40)

    if call("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        parser.error("emulator_required")
    call("shell", "am", "force-stop", "com.ahwotel")

    def snapshot(folder):
        folder.mkdir(parents=True, exist_ok=False)
        names = call("shell", "run-as", "com.ahwotel", "ls", "databases").decode().split()
        for name in ("monitor.db", "monitor.db-wal"):
            if name in names:
                (folder / name).write_bytes(call("exec-out", "run-as", "com.ahwotel", "cat", "databases/" + name))
        (folder / "settings.preferences_pb").write_bytes(call("exec-out", "run-as", "com.ahwotel", "cat", "files/datastore/settings.preferences_pb"))

    def upload(local, remote):
        subprocess.run(adb + ["shell", "-T", "run-as", "com.ahwotel", "tee", remote],
                       input=local.read_bytes(), stdout=subprocess.DEVNULL, check=True, timeout=40)
        if call("exec-out", "run-as", "com.ahwotel", "cat", remote) != local.read_bytes():
            raise AssertionError("fixture_upload_truncated")

    if args.stage == "prepare":
        original = args.evidence / "original"
        snapshot(original)
        original_summary = digest(original)
        if any(original_summary[t]["count"] for t in TABLES):
            parser.error("empty_emulator_profile_required; existing rows were not modified")
        raw = (original / "settings.preferences_pb").read_bytes()
        config = json.loads(raw[raw.index(b"{"):raw.rindex(b"}") + 1])
        config.update(retentionDays=90, otlpEnabled=True, endpoint="https://isolation.invalid/v1/metrics", language="ru")
        encoded = json.dumps(config, ensure_ascii=False, separators=(",", ":"))
        seed = args.evidence / "seed"
        seed.mkdir()
        with sqlite3.connect(original / "monitor.db") as src, sqlite3.connect(seed / "monitor.db") as db:
            src.backup(db)
            now = int(call("shell", "date", "+%s").strip()) * 1000
            old = now - 30 * 86400000
            db.execute("insert into sessions(id,deviceId,startedAt,endedAt,status,reason,configuration,continuous,durationSeconds,endReason) values(?,?,?,?,?,?,?,?,?,?)",
                       ("isolation-sentinel", config["deviceId"], old, old + 1000, "FINISHED", "synthetic", encoded, 0, 1, "timeout"))
            db.execute("insert into samples(sessionId,time,elapsed,segment,memoryAvailable,capabilities,screenOn) values(?,?,?,?,?,?,?)",
                       ("isolation-sentinel", old, 1000, 0, 123456789, "MEMORY=AVAILABLE", 1))
            db.execute("insert into outbox(createdAt,endpoint,payload,attempts,nextAttempt) values(?,?,?,?,?)",
                       (now, config["endpoint"], b"synthetic-pending-payload", 0, now + 86400000))
            db.commit()
            db.execute("pragma wal_checkpoint(truncate)")
            db.execute("pragma journal_mode=delete")
        (seed / "settings.preferences_pb").write_bytes(preferences(encoded))
        upload(seed / "monitor.db", "databases/monitor.db")
        # The process is stopped; discard its obsolete WAL, never a user's live database.
        call("shell", "run-as", "com.ahwotel", "rm", "-f", "databases/monitor.db-wal", "databases/monitor.db-shm")
        upload(seed / "settings.preferences_pb", "files/datastore/settings.preferences_pb")
        snapshot(args.evidence / "before")
        result = digest(args.evidence / "before")
        if result != digest(seed):
            raise AssertionError("fixture_upload_mismatch")
        (args.evidence / "expected.json").write_text(json.dumps(result, indent=2) + "\n")
        print("PREPARED: 90-day retention, 30-day-old sample/session and pending OTLP payload on emulator")
    else:
        folder = args.evidence / ("after-" + str(time.time_ns()))
        snapshot(folder)
        expected = json.loads((args.evidence / "expected.json").read_text())
        result = digest(folder)
        (folder / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
        if result != expected:
            raise AssertionError(("working_profile_changed", expected, result))
        print("PASS: all working-profile rows, pending payload, Room version and settings are unchanged")


if __name__ == "__main__":
    main()
