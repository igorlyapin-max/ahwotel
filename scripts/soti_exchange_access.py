#!/usr/bin/env python3
"""Safely inspect and approve SOTI-managed devices for Exchange access."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import stat
import sys
import tempfile
import time
from typing import Any, Iterable
import urllib.error
import urllib.parse
import urllib.request
import uuid

SCHEMA_VERSION = 1
DIAGNOSTIC_LEVELS = {"Off", "Basic", "Verbose"}
SENSITIVE_KEYS = re.compile(r"password|secret|token|authorization|email|account", re.I)


class ContractError(RuntimeError):
    pass


class ApiError(RuntimeError):
    pass


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso(value: dt.datetime) -> str:
    return value.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def parse_iso(value: str) -> dt.datetime:
    if not isinstance(value, str):
        raise ContractError("invalid_timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ContractError("invalid_timestamp") from error
    if parsed.tzinfo is None:
        raise ContractError("timestamp_must_include_timezone")
    return parsed.astimezone(dt.timezone.utc)


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
        os.fchmod(handle.fileno(), stat.S_IRUSR | stat.S_IWUSR)
        temporary = Path(handle.name)
    os.replace(temporary, path)


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(f"invalid_json:{path.name}") from error


def read_secret(path_value: str | None, prompt: str, *, interactive: bool) -> str:
    if not path_value:
        if interactive and sys.stdin.isatty():
            value = getpass.getpass(prompt)
            if value:
                return value
        raise ContractError("secret_file_required")
    path = Path(path_value)
    try:
        mode = stat.S_IMODE(path.stat().st_mode)
        if mode & (stat.S_IRWXG | stat.S_IRWXO):
            raise ContractError(f"secret_file_permissions_must_be_0600:{path.name}")
        value = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise ContractError(f"secret_file_unreadable:{path.name}") from error
    if not value:
        raise ContractError(f"secret_file_empty:{path.name}")
    return value


def require_string_list(config: dict[str, Any], name: str) -> list[str]:
    value = config.get(name)
    if not isinstance(value, list) or not value or not all(isinstance(item, str) and item for item in value):
        raise ContractError(f"invalid_config:{name}")
    return value


def validate_url(value: Any, *, name: str, require_api_path: bool = False) -> str:
    if not isinstance(value, str):
        raise ContractError(f"invalid_https_url:{name}")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ContractError(f"invalid_https_url:{name}")
    if parsed.query or parsed.fragment:
        raise ContractError(f"url_query_or_fragment_forbidden:{name}")
    if require_api_path and not parsed.path.rstrip("/").endswith("/MobiControl/api"):
        raise ContractError("baseUrl_must_end_with_MobiControl_api")
    return value.rstrip("/")


def validate_relative_path(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value or value.startswith(("/", "http:", "https:")) or ".." in value:
        raise ContractError(f"invalid_config:{name}")
    return value


def validate_template(value: Any, name: str) -> str:
    value = validate_relative_path(value, name)
    if set(re.findall(r"\{([^{}]+)\}", value)) != {"deviceId"}:
        raise ContractError(f"invalid_config:{name}_placeholders")
    return value


def validate_config(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ContractError("config_must_be_object")
    forbidden = [key for key in raw if SENSITIVE_KEYS.search(key)]
    if forbidden:
        raise ContractError("secrets_or_identity_fields_forbidden_in_config")
    config = dict(raw)
    config["baseUrl"] = validate_url(config.get("baseUrl"), name="baseUrl", require_api_path=True)
    for name in (
        "corporateGroupPrefixes", "allowedManagementModes", "activeDeviceStatuses",
        "exchangeReferenceIds", "quarantineStatuses", "allowedExchangeStatuses",
    ):
        require_string_list(config, name)
    fields = config.get("fields")
    required_fields = {
        "deviceId", "groupPath", "managementMode", "deviceStatus", "exchangeAccess",
        "exchangeReferenceId", "exchangeStatus",
    }
    if not isinstance(fields, dict) or set(fields) != required_fields or not all(isinstance(v, str) and v for v in fields.values()):
        raise ContractError("invalid_config:fields")
    config["diagnostic"] = config.get("diagnostic", "Off")
    if config["diagnostic"] not in DIAGNOSTIC_LEVELS:
        raise ContractError("invalid_config:diagnostic")
    for name, default, low, high in (
        ("reportTtlMinutes", 30, 1, 1440), ("requestTimeoutSeconds", 20, 1, 120),
        ("verifyTimeoutSeconds", 60, 1, 600), ("verifyPollSeconds", 3, 1, 60),
    ):
        value = config.get(name, default)
        if not isinstance(value, int) or not low <= value <= high:
            raise ContractError(f"invalid_config:{name}")
        config[name] = value
    config["devicesPath"] = validate_relative_path(config.get("devicesPath", "devices"), "devicesPath")
    config["devicePathTemplate"] = validate_template(config.get("devicePathTemplate", "devices/{deviceId}"), "devicePathTemplate")
    config["actionPathTemplate"] = validate_template(config.get("actionPathTemplate", "devices/{deviceId}/actions"), "actionPathTemplate")
    excluded = config.get("excludedDeviceIds", [])
    if not isinstance(excluded, list) or not all(isinstance(item, str) and item for item in excluded):
        raise ContractError("invalid_config:excludedDeviceIds")
    config["excludedDeviceIds"] = excluded
    if not isinstance(config.get("actionContractVerified", False), bool):
        raise ContractError("invalid_config:actionContractVerified")
    payload = config.get("actionPayloadTemplate")
    if payload is not None and not isinstance(payload, dict):
        raise ContractError("invalid_config:actionPayloadTemplate")
    placeholders = set(re.findall(r"\{([^{}]+)\}", json.dumps(payload))) if payload else set()
    if not placeholders <= {"deviceId", "referenceId"}:
        raise ContractError("invalid_config:actionPayloadTemplate_placeholders")
    if config.get("actionContractVerified") and not payload:
        raise ContractError("verified_action_requires_payload_template")
    endpoint = config.get("otlpLogsEndpoint")
    if endpoint:
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
            raise ContractError("invalid_otlpLogsEndpoint")
        if parsed.query or parsed.fragment or not parsed.path.endswith("/v1/logs"):
            raise ContractError("otlpLogsEndpoint_must_end_with_v1_logs")
    return config


def dotted(value: Any, path: str) -> Any:
    current = value
    for segment in path.split("."):
        if not isinstance(current, dict) or segment not in current:
            raise ContractError(f"missing_response_field:{path}")
        current = current[segment]
    return current


def normalize_devices(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, list):
        items = value
    elif isinstance(value, dict):
        for name in ("Next", "next", "NextPage", "nextPage", "ContinuationToken", "continuationToken"):
            if value.get(name):
                raise ContractError("device_pagination_not_supported_use_filtered_path")
        keys = [key for key in ("Items", "items", "Devices", "devices", "Data", "data") if key in value]
        if len(keys) != 1 or not isinstance(value[keys[0]], list):
            raise ContractError("unsupported_device_collection_shape")
        items = value[keys[0]]
        for name in ("Total", "total", "TotalCount", "totalCount", "TotalResults", "totalResults"):
            total = value.get(name)
            if total is not None and (not isinstance(total, int) or total != len(items)):
                raise ContractError("device_collection_may_be_partial")
    else:
        raise ContractError("unsupported_device_collection_shape")
    if not all(isinstance(item, dict) for item in items):
        raise ContractError("device_collection_contains_non_object")
    return items


def group_matches(path: str, prefixes: Iterable[str]) -> bool:
    normalized = path.rstrip("/")
    return any(normalized == prefix.rstrip("/") or normalized.startswith(prefix.rstrip("/") + "/") for prefix in prefixes)


def policy_material(config: dict[str, Any]) -> dict[str, Any]:
    names = (
        "baseUrl", "corporateGroupPrefixes", "allowedManagementModes", "activeDeviceStatuses",
        "exchangeReferenceIds", "quarantineStatuses", "allowedExchangeStatuses", "fields",
        "devicesPath", "devicePathTemplate", "actionPathTemplate", "actionPayloadTemplate",
        "excludedDeviceIds", "actionContractVerified",
    )
    return {name: config.get(name) for name in names}


def policy_fingerprint(config: dict[str, Any]) -> str:
    encoded = json.dumps(policy_material(config), sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def snapshot_id(policy: str, generated_at: str, expires_at: str, candidates: Any) -> str:
    encoded = json.dumps({
        "policy": policy, "generatedAt": generated_at, "expiresAt": expires_at,
        "candidates": candidates,
    }, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def evaluate_device(device: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    fields = config["fields"]
    device_id = str(dotted(device, fields["deviceId"]))
    group_path = str(dotted(device, fields["groupPath"]))
    management_mode = str(dotted(device, fields["managementMode"]))
    device_status = str(dotted(device, fields["deviceStatus"]))
    base = {"deviceId": device_id, "groupPath": group_path, "managementMode": management_mode, "deviceStatus": device_status}
    if device_id in config["excludedDeviceIds"]:
        return {**base, "decision": "skip", "reason": "manual_exclusion"}
    if not group_matches(group_path, config["corporateGroupPrefixes"]):
        return {**base, "decision": "skip", "reason": "non_corporate_group"}
    if management_mode not in config["allowedManagementModes"]:
        return {**base, "decision": "skip", "reason": "management_mode_not_allowed"}
    if device_status not in config["activeDeviceStatuses"]:
        return {**base, "decision": "skip", "reason": "device_not_active"}
    records = dotted(device, fields["exchangeAccess"])
    if not isinstance(records, list):
        raise ContractError("exchange_access_must_be_array")
    matching = []
    for record in records:
        if not isinstance(record, dict):
            raise ContractError("exchange_access_contains_non_object")
        if str(dotted(record, fields["exchangeReferenceId"])) in config["exchangeReferenceIds"]:
            matching.append(record)
    if len(matching) != 1:
        return {**base, "decision": "manual", "reason": "exchange_record_count", "recordCount": len(matching)}
    reference_id = str(dotted(matching[0], fields["exchangeReferenceId"]))
    exchange_status = str(dotted(matching[0], fields["exchangeStatus"]))
    result = {**base, "referenceId": reference_id, "exchangeStatus": exchange_status}
    if exchange_status in config["allowedExchangeStatuses"]:
        return {**result, "decision": "noop", "reason": "already_allowed"}
    if exchange_status not in config["quarantineStatuses"]:
        return {**result, "decision": "manual", "reason": "unexpected_exchange_status"}
    return {**result, "decision": "candidate", "reason": "corporate_quarantined"}


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if SENSITIVE_KEYS.search(key):
                result[key] = "***"
            elif key.lower() == "deviceid":
                result[key] = "sha256:" + hashlib.sha256(str(item).encode()).hexdigest()[:12]
            else:
                result[key] = redact(item)
        return result
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


def validate_otlp_response(raw: bytes) -> None:
    if not raw:
        return
    try:
        response = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ApiError("otlp_response_not_json") from error
    partial = response.get("partialSuccess", response.get("partial_success", {})) if isinstance(response, dict) else None
    if not isinstance(partial, dict):
        raise ApiError("otlp_response_invalid")
    rejected = partial.get("rejectedLogRecords", partial.get("rejected_log_records", 0))
    if rejected or partial.get("errorMessage") or partial.get("error_message"):
        raise ApiError("otlp_logs_rejected")


class EventLogger:
    def __init__(self, config: dict[str, Any], run_id: str):
        self.level = config["diagnostic"]
        self.endpoint = config.get("otlpLogsEndpoint")
        self.timeout = config["requestTimeoutSeconds"]
        self.run_id = run_id

    def emit(self, event: str, *, severity: str = "INFO", required_sink: bool = False, **metadata: Any) -> None:
        record = {"timestamp": iso(utc_now()), "severity": severity, "event": event, "runId": self.run_id, "metadata": redact(metadata)}
        print(json.dumps(record, sort_keys=True, separators=(",", ":")), file=sys.stderr if severity in {"ERROR", "WARN"} else sys.stdout, flush=True)
        if self.endpoint:
            try:
                self._send_otlp(record)
            except Exception as error:
                if required_sink:
                    raise ApiError("external_audit_sink_unavailable") from error
                warning = {"timestamp": iso(utc_now()), "severity": "WARN", "event": "audit_sink_unavailable", "runId": self.run_id}
                print(json.dumps(warning, sort_keys=True, separators=(",", ":")), file=sys.stderr, flush=True)
        elif required_sink:
            raise ContractError("apply_requires_otlpLogsEndpoint")

    def diagnostic(self, event: str, *, verbose: bool = False, **metadata: Any) -> None:
        if self.level == "Verbose" or (self.level == "Basic" and not verbose):
            self.emit(event, **metadata)

    def _send_otlp(self, record: dict[str, Any]) -> None:
        body = json.dumps({"resourceLogs": [{
            "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "ahwotel-soti-exchange-access"}}]},
            "scopeLogs": [{"scope": {"name": "ahwotel.soti.exchange"}, "logRecords": [{
                "timeUnixNano": str(time.time_ns()), "severityText": record["severity"],
                "body": {"stringValue": json.dumps(record, sort_keys=True, separators=(",", ":"))},
            }]}],
        }]}).encode()
        request = urllib.request.Request(self.endpoint, data=body, method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=self.timeout, context=ssl.create_default_context()) as response:
            if not 200 <= response.status < 300:
                raise ApiError(f"otlp_http_status:{response.status}")
            validate_otlp_response(response.read())


class SotiClient:
    def __init__(self, config: dict[str, Any], logger: EventLogger, *, interactive: bool):
        self.base_url = config["baseUrl"]
        self.timeout = config["requestTimeoutSeconds"]
        self.config = config
        self.logger = logger
        self.client_id = os.environ.get("SOTI_CLIENT_ID", "")
        self.username = os.environ.get("SOTI_USERNAME", "")
        if not self.client_id or not self.username:
            raise ContractError("SOTI_CLIENT_ID_and_SOTI_USERNAME_required")
        self.client_secret = read_secret(os.environ.get("SOTI_CLIENT_SECRET_FILE"), "SOTI client secret: ", interactive=interactive)
        self.password = read_secret(os.environ.get("SOTI_PASSWORD_FILE"), "SOTI user password: ", interactive=interactive)
        self.token: str | None = None

    def authenticate(self) -> None:
        data = urllib.parse.urlencode({"grant_type": "password", "username": self.username, "password": self.password}).encode()
        basic = base64.b64encode(f"{self.client_id}:{self.client_secret}".encode()).decode()
        value = self._raw("POST", "token", data=data, headers={"Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"})
        if not isinstance(value, dict) or not isinstance(value.get("access_token"), str) or not value["access_token"]:
            raise ContractError("token_response_missing_access_token")
        self.token = value["access_token"]
        self.logger.emit("soti_auth_succeeded")

    def list_devices(self) -> list[dict[str, Any]]:
        return normalize_devices(self.request("GET", self.config["devicesPath"]))

    def get_device(self, device_id: str) -> dict[str, Any]:
        path = self.config["devicePathTemplate"].format(deviceId=urllib.parse.quote(device_id, safe=""))
        value = self.request("GET", path)
        if not isinstance(value, dict):
            raise ContractError("device_response_must_be_object")
        return value

    def allow_exchange(self, device_id: str, reference_id: str) -> Any:
        if not self.config["actionContractVerified"]:
            raise ContractError("action_contract_not_verified")
        path = self.config["actionPathTemplate"].format(deviceId=urllib.parse.quote(device_id, safe=""))
        payload = substitute(self.config["actionPayloadTemplate"], device_id, reference_id)
        return self.request("POST", path, payload)

    def request(self, method: str, path: str, payload: Any = None) -> Any:
        if self.token is None:
            self.authenticate()
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Authorization": "Bearer " + self.token}
        if data is not None:
            headers["Content-Type"] = "application/json"
        return self._raw(method, path, data=data, headers=headers)

    def _raw(self, method: str, path: str, *, data: bytes | None, headers: dict[str, str]) -> Any:
        request = urllib.request.Request(self.base_url + "/" + path.lstrip("/"), data=data, method=method, headers={**headers, "Accept": "application/json"})
        self.logger.diagnostic("soti_request", method=method, path=path)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout, context=ssl.create_default_context()) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            raise ApiError(f"soti_http_status:{error.code}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise ApiError("soti_transport_error") from error
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError as error:
            raise ContractError("soti_response_not_json") from error


def substitute(value: Any, device_id: str, reference_id: str) -> Any:
    if isinstance(value, dict):
        return {key: substitute(item, device_id, reference_id) for key, item in value.items()}
    if isinstance(value, list):
        return [substitute(item, device_id, reference_id) for item in value]
    if isinstance(value, str):
        return value.replace("{deviceId}", device_id).replace("{referenceId}", reference_id)
    return value


def inspect_devices(client: Any, config: dict[str, Any], logger: EventLogger) -> dict[str, Any]:
    now = utc_now()
    decisions = sorted((evaluate_device(device, config) for device in client.list_devices()), key=lambda item: item["deviceId"])
    candidates = [{"deviceId": item["deviceId"], "referenceId": item["referenceId"]} for item in decisions if item["decision"] == "candidate"]
    generated_at = iso(now)
    expires_at = iso(now + dt.timedelta(minutes=config["reportTtlMinutes"]))
    policy = policy_fingerprint(config)
    report = {
        "schemaVersion": SCHEMA_VERSION, "kind": "soti-exchange-inspection", "runId": logger.run_id,
        "generatedAt": generated_at, "expiresAt": expires_at, "policyFingerprint": policy,
        "snapshotId": snapshot_id(policy, generated_at, expires_at, candidates),
        "candidates": candidates, "decisions": decisions,
    }
    logger.emit("inspection_completed", devices=len(decisions), candidates=len(candidates))
    return report


def validate_approval(report: Any, approval: Any, config: dict[str, Any], *, now: dt.datetime | None = None) -> list[dict[str, str]]:
    if not isinstance(report, dict) or report.get("schemaVersion") != SCHEMA_VERSION or report.get("kind") != "soti-exchange-inspection":
        raise ContractError("invalid_inspection_report")
    if report.get("policyFingerprint") != policy_fingerprint(config):
        raise ContractError("inspection_policy_changed")
    generated_at = report.get("generatedAt")
    expires_at = report.get("expiresAt")
    parse_iso(generated_at)
    expiry = parse_iso(expires_at)
    candidates = report.get("candidates")
    if not isinstance(candidates, list) or any(
        not isinstance(item, dict) or set(item) != {"deviceId", "referenceId"}
        or not all(isinstance(value, str) and value for value in item.values()) for item in candidates
    ):
        raise ContractError("invalid_inspection_candidates")
    expected_snapshot = snapshot_id(report["policyFingerprint"], generated_at, expires_at, candidates)
    if report.get("snapshotId") != expected_snapshot:
        raise ContractError("inspection_snapshot_invalid")
    if expiry <= (now or utc_now()):
        raise ContractError("inspection_report_expired")
    if not isinstance(approval, dict) or approval.get("schemaVersion") != SCHEMA_VERSION:
        raise ContractError("invalid_approval_file")
    if approval.get("snapshotId") != report.get("snapshotId"):
        raise ContractError("approval_snapshot_mismatch")
    values = approval.get("approvals")
    if not isinstance(values, list) or not values:
        raise ContractError("approval_list_empty")
    allowed = {(item["deviceId"], item["referenceId"]) for item in candidates}
    result, seen = [], set()
    for item in values:
        if not isinstance(item, dict) or set(item) != {"deviceId", "referenceId"}:
            raise ContractError("invalid_approval_item")
        pair = (item["deviceId"], item["referenceId"])
        if pair not in allowed or pair in seen:
            raise ContractError("approval_not_unique_candidate")
        seen.add(pair)
        result.append({"deviceId": pair[0], "referenceId": pair[1]})
    return result


def wait_for_allowed(client: Any, config: dict[str, Any], device_id: str, reference_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + config["verifyTimeoutSeconds"]
    while True:
        decision = evaluate_device(client.get_device(device_id), config)
        if decision.get("referenceId") != reference_id:
            raise ContractError("reference_id_changed_after_action")
        if decision["decision"] == "noop":
            return decision
        if decision["decision"] != "candidate":
            raise ContractError("unexpected_state_after_action")
        if time.monotonic() >= deadline:
            raise ApiError("exchange_access_verification_timeout")
        time.sleep(config["verifyPollSeconds"])


def apply_approvals(client: Any, config: dict[str, Any], logger: EventLogger, approvals: list[dict[str, str]]) -> dict[str, Any]:
    if not config["actionContractVerified"]:
        raise ContractError("action_contract_not_verified")
    logger.emit("apply_audit_preflight", required_sink=True, approvals=len(approvals))
    results = []
    for approved in approvals:
        device_id, reference_id = approved["deviceId"], approved["referenceId"]
        current = evaluate_device(client.get_device(device_id), config)
        if current["decision"] == "noop":
            if current.get("referenceId") != reference_id:
                raise ContractError("approved_reference_id_changed")
            outcome = "already_allowed"
        elif current["decision"] == "candidate":
            if current.get("referenceId") != reference_id:
                raise ContractError("approved_reference_id_changed")
            try:
                client.allow_exchange(device_id, reference_id)
            except ApiError:
                reread = evaluate_device(client.get_device(device_id), config)
                if reread["decision"] != "noop":
                    logger.emit("exchange_action_ambiguous", severity="ERROR", deviceId=device_id)
                    raise ApiError("exchange_action_ambiguous_manual_review_required")
            wait_for_allowed(client, config, device_id, reference_id)
            outcome = "allowed"
        else:
            raise ContractError(f"device_no_longer_eligible:{current['reason']}")
        result = {"deviceId": device_id, "referenceId": reference_id, "outcome": outcome}
        results.append(result)
        logger.emit("exchange_access_result", **result)
    return {"schemaVersion": SCHEMA_VERSION, "kind": "soti-exchange-apply-result", "runId": logger.run_id, "completedAt": iso(utc_now()), "results": results}


def verify_devices(client: Any, config: dict[str, Any], approvals: list[dict[str, str]], logger: EventLogger) -> dict[str, Any]:
    results = []
    for approved in approvals:
        decision = evaluate_device(client.get_device(approved["deviceId"]), config)
        allowed = decision.get("referenceId") == approved["referenceId"] and decision["decision"] == "noop"
        results.append({"deviceId": approved["deviceId"], "referenceId": approved["referenceId"], "allowed": allowed, "reason": decision["reason"]})
    logger.emit("verification_completed", devices=len(results), allowed=sum(item["allowed"] for item in results))
    return {"schemaVersion": SCHEMA_VERSION, "kind": "soti-exchange-verification", "runId": logger.run_id, "completedAt": iso(utc_now()), "results": results}


def build_parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--config", type=Path, required=True)
    commands = root.add_subparsers(dest="command", required=True)
    auth = commands.add_parser("auth-check")
    auth.add_argument("--device-id", required=True)
    inspect = commands.add_parser("inspect")
    inspect.add_argument("--output", type=Path, required=True)
    apply = commands.add_parser("apply")
    apply.add_argument("--report", type=Path, required=True)
    apply.add_argument("--approvals", type=Path, required=True)
    apply.add_argument("--output", type=Path, required=True)
    apply.add_argument("--confirm", choices=["APPLY"], required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--report", type=Path, required=True)
    verify.add_argument("--approvals", type=Path, required=True)
    verify.add_argument("--output", type=Path, required=True)
    return root


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    run_id = str(uuid.uuid4())
    try:
        config = validate_config(read_json(args.config))
        logger = EventLogger(config, run_id)
        approvals = None
        if args.command in {"apply", "verify"}:
            report, approval = read_json(args.report), read_json(args.approvals)
            approvals = validate_approval(report, approval, config)
            if args.command == "apply" and not config["actionContractVerified"]:
                raise ContractError("action_contract_not_verified")
        client = SotiClient(config, logger, interactive=True)
        if args.command == "auth-check":
            client.authenticate()
            decision = evaluate_device(client.get_device(args.device_id), config)
            logger.emit("auth_check_completed", deviceReadable=True, decision=decision["decision"], reason=decision["reason"])
        elif args.command == "inspect":
            atomic_json(args.output, inspect_devices(client, config, logger))
        else:
            assert approvals is not None
            result = apply_approvals(client, config, logger, approvals) if args.command == "apply" else verify_devices(client, config, approvals, logger)
            atomic_json(args.output, result)
        return 0
    except (ContractError, ApiError) as error:
        record = {"timestamp": iso(utc_now()), "severity": "ERROR", "event": "command_failed", "runId": run_id, "error": str(error)}
        print(json.dumps(record, sort_keys=True, separators=(",", ":")), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
