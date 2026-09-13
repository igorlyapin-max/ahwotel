#!/usr/bin/env python3
"""Install and run instrumentation only in the isolated acceptance package."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.ahwotel.acceptance"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = "com.ahwotel.SafeTestRunner"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def validate_manifests(app_xml, test_xml):
    app, test = ET.fromstring(app_xml), ET.fromstring(test_xml)
    if app.get("package") != PACKAGE or test.get("package") != TEST_PACKAGE:
        raise ValueError("unsafe_instrumentation_package")
    runners = test.findall("instrumentation")
    if (len(runners) != 1 or runners[0].get(ANDROID + "targetPackage") != PACKAGE
            or runners[0].get(ANDROID + "name") != RUNNER):
        raise ValueError("unsafe_instrumentation_target")
    application = app.find("application")
    if application is None or application.get(ANDROID + "name") != "com.ahwotel.MonitorApp":
        raise ValueError("unexpected_test_application")


def manifest(apk):
    env = os.environ.copy()
    env.setdefault("JAVA_HOME", str(ROOT / ".tools/jdk"))
    sdk = Path(env.get("ANDROID_HOME", ROOT / ".tools/android"))
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    return subprocess.check_output([str(analyzer), "manifest", "print", str(apk)], env=env, text=True)


def execute(serial, report, test_class, app_apk, test_apk, *, verify_only=False):
    # Complete BOTH checks before even connecting to a device or installing an APK.
    validate_manifests(manifest(app_apk), manifest(test_apk))
    print(f"Verified isolated instrumentation: {TEST_PACKAGE} -> {PACKAGE}", flush=True)
    if verify_only:
        return
    adb = [str(ROOT / "scripts/adb.sh"), "-s", serial]
    subprocess.run(adb + ["install", "-r", str(app_apk)], check=True)
    subprocess.run(adb + ["install", "-r", str(test_apk)], check=True)
    installed = subprocess.check_output(adb + ["shell", "pm", "list", "instrumentation"], text=True)
    expected = f"instrumentation:{TEST_PACKAGE}/{RUNNER} (target={PACKAGE})"
    if expected not in installed.splitlines():
        raise ValueError("installed_instrumentation_mismatch")
    args = ["shell", "am", "instrument", "-w", "-r"]
    if test_class:
        args += ["-e", "class", test_class]
    else:
        # Real TalkBack requires a prepared API 35 emulator and is explicitly opt-in.
        args += ["-e", "notClass", "com.ahwotel.TalkBackHelpAcceptanceTest"]
    args += [f"{TEST_PACKAGE}/{RUNNER}"]
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("w") as out:
        process = subprocess.Popen(adb + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        transcript = []
        try:
            for line in process.stdout:
                print(line, end="", flush=True)
                out.write(line)
                transcript.append(line)
            code = process.wait()
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait()
    # am instrument can return 0 even when tests fail.
    if code or not re.search(r"^OK \([1-9][0-9]* tests?\)$", "".join(transcript), re.M):
        raise RuntimeError("instrumentation_failed; see " + str(report))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("report", nargs="?", type=Path, default=ROOT / "artifacts/android-instrumentation.txt")
    parser.add_argument("test_class", nargs="?", default="")
    parser.add_argument("--app-apk", type=Path, default=ROOT / "app/build/outputs/apk/acceptance/app-acceptance.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    # ADB sends shell arguments as a command string; disallow shell metacharacters.
    if args.test_class and not re.fullmatch(r"[A-Za-z0-9_.#]+(?:,[A-Za-z0-9_.#]+)*", args.test_class):
        parser.error("invalid_test_filter")
    try:
        execute(args.serial, args.report, args.test_class, args.app_apk, args.test_apk, verify_only=args.verify_only)
    except (ValueError, RuntimeError, subprocess.CalledProcessError, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
