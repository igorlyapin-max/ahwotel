"""Host guard regression: unsafe APKs must cause zero ADB/device operations."""
from pathlib import Path
from tempfile import TemporaryDirectory
import json
from unittest import TestCase, main
from unittest.mock import patch
import instrument


def app(package=instrument.PACKAGE):
    return f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"><application android:name="com.ahwotel.MonitorApp"/></manifest>'


def test(package=instrument.TEST_PACKAGE, target=instrument.PACKAGE, runner=instrument.RUNNER):
    return f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"><instrumentation android:name="{runner}" android:targetPackage="{target}"/></manifest>'


class InstrumentGuardTest(TestCase):
    def test_rejects_unsafe_packages_targets_runners_before_device_operations(self):
        cases = [(app("com.ahwotel"), test()), (app(), test(target="com.ahwotel")),
                 (app(), test(package="com.ahwotel.test")),
                 (app(), test(runner="androidx.test.runner.AndroidJUnitRunner")),
                 (app(), '<manifest package="com.ahwotel.acceptance.test"/>'),
                 (app(), "broken xml")]
        for manifests in cases:
            with self.subTest(manifests=manifests), patch.object(instrument, "manifest", side_effect=manifests), \
                    patch.object(instrument.subprocess, "run") as run, \
                    patch.object(instrument.subprocess, "check_output") as read, \
                    patch.object(instrument.subprocess, "Popen") as start:
                with TemporaryDirectory() as folder, self.assertRaises((ValueError, instrument.ET.ParseError)):
                    instrument.execute("fake", Path(folder)/"android.txt", "", Path("app.apk"), Path("test.apk"))
                run.assert_not_called()
                read.assert_not_called()
                start.assert_not_called()

    def test_isolated_manifest_is_accepted(self):
        instrument.validate_manifests(app(), test())

    def test_failed_preflight_invalidates_previous_run_but_verify_only_preserves_it(self):
        with TemporaryDirectory() as folder:
            report = Path(folder)/"android.txt"
            record = report.with_suffix(".run.json")
            record.write_text('{"passed":true,"runId":"previous"}')
            report.write_text("OK (1 test)")
            for verify_only in (True, False):
                with patch.object(instrument, "manifest", side_effect=ValueError("invalid_apk")):
                    with self.assertRaisesRegex(ValueError, "invalid_apk"):
                        instrument.execute("device", report, "", Path("app.apk"), Path("test.apk"),
                                           verify_only=verify_only)
                current = json.loads(record.read_text())
                self.assertEqual(verify_only, current["passed"])
                self.assertEqual(verify_only, current["runId"] == "previous")
                self.assertEqual(verify_only, report.read_text() == "OK (1 test)")


if __name__ == "__main__":
    main()
