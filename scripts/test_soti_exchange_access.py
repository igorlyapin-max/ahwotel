#!/usr/bin/env python3
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import soti_exchange_access as access


def config(**overrides):
    value = {
        "baseUrl": "https://soti.example.test/MobiControl/api",
        "corporateGroupPrefixes": ["/Corporate"],
        "allowedManagementModes": ["DeviceOwner"],
        "activeDeviceStatuses": ["Active"],
        "exchangeReferenceIds": ["exchange-main"],
        "quarantineStatuses": ["Quarantined"],
        "allowedExchangeStatuses": ["Allowed"],
        "excludedDeviceIds": [],
        "diagnostic": "Off",
        "reportTtlMinutes": 30,
        "requestTimeoutSeconds": 5,
        "verifyTimeoutSeconds": 1,
        "verifyPollSeconds": 1,
        "devicesPath": "devices",
        "devicePathTemplate": "devices/{deviceId}",
        "actionPathTemplate": "devices/{deviceId}/actions",
        "actionContractVerified": True,
        "actionPayloadTemplate": {"Action": "AllowExchangeAccess", "ReferenceId": "{referenceId}"},
        "otlpLogsEndpoint": "http://collector.example.test:4318/v1/logs",
        "fields": {
            "deviceId": "DeviceId", "groupPath": "GroupPath", "managementMode": "ManagementMode",
            "deviceStatus": "Status", "exchangeAccess": "ExchangeOnlineEmailAccess",
            "exchangeReferenceId": "ReferenceId", "exchangeStatus": "Status",
        },
    }
    value.update(overrides)
    return access.validate_config(value)


def device(device_id="d-1", *, group="/Corporate/Tablets", mode="DeviceOwner", status="Active", exchange_status="Quarantined", records=1):
    return {
        "DeviceId": device_id, "GroupPath": group, "ManagementMode": mode, "Status": status,
        "ExchangeOnlineEmailAccess": [
            {"ReferenceId": "exchange-main", "Status": exchange_status} for _ in range(records)
        ],
    }


class Logger:
    run_id = "run-1"

    def __init__(self):
        self.events = []

    def emit(self, event, **metadata):
        self.events.append((event, metadata))


class FakeClient:
    def __init__(self, devices):
        self.devices = {item["DeviceId"]: item for item in devices}
        self.actions = []

    def list_devices(self):
        return list(self.devices.values())

    def get_device(self, device_id):
        return self.devices[device_id]

    def allow_exchange(self, device_id, reference_id):
        self.actions.append((device_id, reference_id))
        self.devices[device_id]["ExchangeOnlineEmailAccess"][0]["Status"] = "Allowed"


class AmbiguousClient(FakeClient):
    def allow_exchange(self, device_id, reference_id):
        self.actions.append((device_id, reference_id))
        raise access.ApiError("soti_transport_error")


class SotiExchangeAccessTest(unittest.TestCase):
    def test_windows_powershell_51_source_uses_framework_compatible_contract(self):
        scripts_dir = Path(__file__).parent
        main = (scripts_dir / "soti_exchange_access_5_1.ps1").read_text(encoding="ascii")
        tests = (scripts_dir / "test_soti_exchange_access_5_1.ps1").read_text(encoding="ascii")
        self.assertTrue(main.startswith("#requires -Version 5.1"))
        self.assertTrue(tests.startswith("#requires -Version 5.1"))
        for forbidden in (
            "System.Text.Json", "Text.Json", "HashData", "ToHexString",
            "UnixFileMode", "FileSystemAclExtensions", "$IsWindows",
            "ToUnixTimeMilliseconds", "ForEach-Object -Parallel", "-AsHashtable",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, main)
                self.assertNotIn(forbidden, tests)
        for required in (
            "System.Web.Script.Serialization.JavaScriptSerializer",
            "[Security.Cryptography.SHA256]::Create()",
            "[IO.File]::SetAccessControl",
            "[Net.SecurityProtocolType]::Tls12",
            "Add-Type -AssemblyName System.Net.Http",
            "windows_powershell_5_1_required",
        ):
            with self.subTest(required=required):
                self.assertIn(required, main)

    def test_cross_runtime_parity_fixture(self):
        fixture_path = Path(__file__).parent / "fixtures" / "soti_exchange_access" / "parity.json"
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        cfg = access.validate_config(fixture["config"])
        self.assertEqual(fixture["expectedPolicyFingerprint"], access.policy_fingerprint(cfg))
        candidates = [{"deviceId": fixture["device"]["DeviceId"], "referenceId": "exchange-main"}]
        self.assertEqual(
            fixture["expectedSnapshotId"],
            access.snapshot_id(
                fixture["expectedPolicyFingerprint"], fixture["generatedAt"], fixture["expiresAt"], candidates
            ),
        )
        self.assertEqual("candidate", access.evaluate_device(fixture["device"], cfg)["decision"])

    def test_candidate_requires_all_corporate_conditions(self):
        cfg = config()
        self.assertEqual("candidate", access.evaluate_device(device(), cfg)["decision"])
        scenarios = [
            (device(group="/Personal"), "non_corporate_group"),
            (device(mode="WorkProfile"), "management_mode_not_allowed"),
            (device(status="Unenrolled"), "device_not_active"),
            (device(records=2), "exchange_record_count"),
            (device(exchange_status="Unknown"), "unexpected_exchange_status"),
        ]
        for value, reason in scenarios:
            with self.subTest(reason=reason):
                self.assertEqual(reason, access.evaluate_device(value, cfg)["reason"])

    def test_exchange_reference_allowlist_is_optional(self):
        cfg = config(exchangeReferenceIds=[])
        decision = access.evaluate_device(device(), cfg)
        self.assertEqual("candidate", decision["decision"])
        self.assertEqual("exchange-main", decision["referenceId"])
        ambiguous = access.evaluate_device(device(records=2), cfg)
        self.assertEqual("manual", ambiguous["decision"])
        self.assertEqual("exchange_record_count", ambiguous["reason"])
        self.assertEqual(2, ambiguous["recordCount"])
        without_key = dict(cfg)
        without_key.pop("exchangeReferenceIds")
        normalized = access.validate_config(without_key)
        self.assertEqual([], normalized["exchangeReferenceIds"])
        self.assertEqual(access.policy_fingerprint(cfg), access.policy_fingerprint(normalized))

    def test_example_config_defaults_to_optional_reference_and_otlp(self):
        example = json.loads((Path(__file__).parents[1] / "docs/soti-exchange-access.example.json").read_text())
        cfg = access.validate_config(example)
        self.assertEqual([], cfg["exchangeReferenceIds"])
        self.assertIsNone(cfg.get("otlpLogsEndpoint"))

    def test_group_prefix_requires_path_boundary(self):
        self.assertFalse(access.group_matches("/Corporate-External", ["/Corporate"]))
        self.assertTrue(access.group_matches("/Corporate/Tablets", ["/Corporate"]))

    def test_collection_rejects_pagination_and_partial_totals(self):
        with self.assertRaisesRegex(access.ContractError, "pagination"):
            access.normalize_devices({"Items": [device()], "Next": "page-2"})
        with self.assertRaisesRegex(access.ContractError, "partial"):
            access.normalize_devices({"Items": [device()], "TotalCount": 2})

    def test_inspection_and_approval_are_bound_to_policy_and_snapshot(self):
        cfg, logger = config(), Logger()
        now = dt.datetime(2026, 10, 21, tzinfo=dt.timezone.utc)
        with patch.object(access, "utc_now", return_value=now):
            report = access.inspect_devices(FakeClient([device(), device("d-2", group="/Personal")]), cfg, logger)
        approval = {"schemaVersion": 1, "snapshotId": report["snapshotId"], "approvals": report["candidates"]}
        self.assertEqual(report["candidates"], access.validate_approval(report, approval, cfg, now=now + dt.timedelta(minutes=1)))
        with self.assertRaisesRegex(access.ContractError, "snapshot"):
            access.validate_approval(report, {**approval, "snapshotId": "changed"}, cfg, now=now)
        changed_report = {**report, "expiresAt": access.iso(now + dt.timedelta(hours=4))}
        with self.assertRaisesRegex(access.ContractError, "snapshot"):
            access.validate_approval(changed_report, approval, cfg, now=now)
        with self.assertRaisesRegex(access.ContractError, "policy"):
            access.validate_approval(report, approval, config(corporateGroupPrefixes=["/Other"]), now=now)

    def test_expired_report_is_rejected(self):
        cfg = config()
        now = dt.datetime(2026, 10, 21, tzinfo=dt.timezone.utc)
        with patch.object(access, "utc_now", return_value=now):
            report = access.inspect_devices(FakeClient([device()]), cfg, Logger())
        approval = {"schemaVersion": 1, "snapshotId": report["snapshotId"], "approvals": report["candidates"]}
        with self.assertRaisesRegex(access.ContractError, "expired"):
            access.validate_approval(report, approval, cfg, now=now + dt.timedelta(minutes=31))

    def test_apply_rechecks_device_and_is_idempotent(self):
        cfg, logger, client = config(), Logger(), FakeClient([device()])
        approval = [{"deviceId": "d-1", "referenceId": "exchange-main"}]
        result = access.apply_approvals(client, cfg, logger, approval)
        self.assertEqual([("d-1", "exchange-main")], client.actions)
        self.assertEqual("allowed", result["results"][0]["outcome"])
        second = access.apply_approvals(client, cfg, logger, approval)
        self.assertEqual(1, len(client.actions))
        self.assertEqual("already_allowed", second["results"][0]["outcome"])

    def test_apply_fails_closed_when_device_changed(self):
        client = FakeClient([device(group="/Personal")])
        with self.assertRaisesRegex(access.ContractError, "no_longer_eligible"):
            access.apply_approvals(client, config(), Logger(), [{"deviceId": "d-1", "referenceId": "exchange-main"}])

    def test_ambiguous_action_is_not_retried(self):
        client = AmbiguousClient([device()])
        with self.assertRaisesRegex(access.ApiError, "manual_review"):
            access.apply_approvals(client, config(), Logger(), [{"deviceId": "d-1", "referenceId": "exchange-main"}])
        self.assertEqual([("d-1", "exchange-main")], client.actions)

    def test_config_rejects_unverified_action_and_insecure_soti_url(self):
        with self.assertRaisesRegex(access.ContractError, "verified_action"):
            config(actionPayloadTemplate=None)
        with self.assertRaisesRegex(access.ContractError, "https"):
            config(baseUrl="http://soti.example.test/MobiControl/api")

    def test_secret_file_requires_private_permissions(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "secret"
            path.write_text("value\n")
            path.chmod(0o644)
            with self.assertRaisesRegex(access.ContractError, "0600"):
                access.read_secret(str(path), "", interactive=False)
            path.chmod(0o600)
            self.assertEqual("value", access.read_secret(str(path), "", interactive=False))

    def test_redaction_masks_sensitive_fields_recursively(self):
        value = access.redact({"token": "x", "deviceId": "d-1", "nested": {"emailAddress": "a@example.test"}, "count": 1})
        self.assertEqual("***", value["token"])
        self.assertEqual("***", value["nested"]["emailAddress"])
        self.assertRegex(value["deviceId"], r"^sha256:[0-9a-f]{12}$")
        self.assertEqual(1, value["count"])

    def test_apply_allows_stdout_only_audit(self):
        cfg = config(otlpLogsEndpoint=None)
        logger = access.EventLogger(cfg, "run-1")
        client = FakeClient([device()])
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            result = access.apply_approvals(client, cfg, logger, [{"deviceId": "d-1", "referenceId": "exchange-main"}])
        self.assertEqual("allowed", result["results"][0]["outcome"])
        self.assertEqual([("d-1", "exchange-main")], client.actions)
        self.assertIn('"auditSink":"stdout"', output.getvalue())

    def test_configured_otlp_failure_warns_and_apply_continues(self):
        cfg = config()
        logger = access.EventLogger(cfg, "run-1")
        client = FakeClient([device()])
        with patch.object(logger, "_send_otlp", side_effect=OSError("collector unavailable")), \
                patch("sys.stdout", new_callable=io.StringIO), patch("sys.stderr", new_callable=io.StringIO) as errors:
            result = access.apply_approvals(client, cfg, logger, [{"deviceId": "d-1", "referenceId": "exchange-main"}])
        self.assertEqual("allowed", result["results"][0]["outcome"])
        self.assertEqual([("d-1", "exchange-main")], client.actions)
        self.assertIn("audit_sink_unavailable", errors.getvalue())

    def test_empty_otlp_endpoint_is_rejected(self):
        with self.assertRaisesRegex(access.ContractError, "invalid_otlpLogsEndpoint"):
            config(otlpLogsEndpoint="")

    def test_otlp_partial_failure_is_rejected(self):
        access.validate_otlp_response(b'{"partialSuccess":{}}')
        with self.assertRaisesRegex(access.ApiError, "rejected"):
            access.validate_otlp_response(b'{"partialSuccess":{"rejectedLogRecords":"1"}}')

    def test_lab_collector_has_durable_log_pipeline(self):
        collector = (Path(__file__).parents[1] / "deploy/otel-lab/collector.yaml").read_text()
        self.assertIn("logs:\n      receivers: [otlp]", collector)
        self.assertIn("file/soti_exchange_audit:", collector)
        self.assertIn("/var/lib/otelcol/soti-exchange-audit.jsonl", collector)


if __name__ == "__main__":
    unittest.main()
