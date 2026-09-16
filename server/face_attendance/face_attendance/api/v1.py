import base64
import hashlib
import json
import secrets
import time
from datetime import datetime
from zoneinfo import ZoneInfo

import frappe
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from frappe import _
from frappe.utils import add_to_date, get_system_timezone, now_datetime

MAX_BATCH_SIZE = 100
AUTHORIZATION_LEASE_SECONDS = 7 * 24 * 60 * 60
REQUIRED_EVENT_FIELDS = {
    "event_id",
    "device_sequence",
    "person_id",
    "device_id",
    "branch_id",
    "gate_id",
    "assignment_version",
    "event_type",
    "captured_at",
    "boot_id",
    "timestamp_confidence",
    "match_score",
    "liveness_score",
    "model_version",
    "template_version",
    "roster_version",
}


@frappe.whitelist(methods=["POST"])
def create_provisioning_token(device_id, validity_minutes=15):
    frappe.only_for("System Manager")
    validity_minutes = int(validity_minutes)
    if not 1 <= validity_minutes <= 60:
        frappe.throw(_("Validity must be between 1 and 60 minutes"))
    device = frappe.get_doc("Attendance Device", device_id)
    token = secrets.token_urlsafe(32)
    frappe.get_doc({
        "doctype": "Device Provisioning Token",
        "device": device.name,
        "token_hash": _token_hash(token),
        "expires_at": add_to_date(now_datetime(), minutes=validity_minutes),
        "issued_by": frappe.session.user,
    }).insert()
    return {"activation_token": token, "expires_in_seconds": validity_minutes * 60}


@frappe.whitelist(allow_guest=True, methods=["POST"])
def activate_device():
    payload = _parse_json_body(frappe.request.get_data(cache=True))
    token = str(payload.get("activation_token") or "")
    public_key = str(payload.get("public_key") or "")
    if not token or not public_key:
        frappe.throw(_("activation_token and public_key are required"))
    _validate_public_key(public_key)

    rows = frappe.db.sql(
        """select name from `tabDevice Provisioning Token`
        where token_hash = %s and used_at is null limit 1 for update""",
        (_token_hash(token),),
    )
    if not rows:
        frappe.throw(_("Invalid or already used activation token"), frappe.AuthenticationError)
    token_doc = frappe.get_doc("Device Provisioning Token", rows[0][0])
    if token_doc.expires_at < now_datetime():
        frappe.throw(_("Activation token has expired"), frappe.AuthenticationError)

    device = frappe.get_doc("Attendance Device", token_doc.device)
    if not device.enabled:
        frappe.throw(_("Device is disabled"), frappe.PermissionError)
    user = frappe.get_doc("User", device.device_user)
    api_key = secrets.token_hex(8)
    api_secret = secrets.token_urlsafe(24)
    user.api_key = api_key
    user.api_secret = api_secret
    user.save(ignore_permissions=True)
    device.db_set("public_key", public_key, update_modified=True)
    token_doc.db_set("used_at", now_datetime(), update_modified=False)

    return {
        "api_key": api_key,
        "api_secret": api_secret,
        "device_id": device.device_id,
        "branch_id": device.branch_id,
        "gate_id": device.gate_id,
        "direction_mode": device.direction_mode,
        "assignment_version": device.assignment_version,
        **_lease(),
    }


@frappe.whitelist(methods=["POST"])
def sync_state():
    raw_body = frappe.request.get_data(cache=True)
    payload = _parse_payload(raw_body)
    device = _authenticated_device(payload.get("device_id"))
    _verify_signature(device.public_key, raw_body, frappe.get_request_header("X-Device-Signature"))
    device.db_set("last_seen", now_datetime(), update_modified=False)
    return {
        "device_id": device.device_id,
        "branch_id": device.branch_id,
        "gate_id": device.gate_id,
        "direction_mode": device.direction_mode,
        "assignment_version": device.assignment_version,
        **_lease(),
    }


@frappe.whitelist(methods=["POST"])
def submit_events():
    raw_body = frappe.request.get_data(cache=True)
    payload = _parse_payload(raw_body)
    device = _authenticated_device(payload.get("device_id"))
    _verify_signature(device.public_key, raw_body, frappe.get_request_header("X-Device-Signature"))

    events = payload.get("events")
    if not isinstance(events, list) or not 1 <= len(events) <= MAX_BATCH_SIZE:
        frappe.throw(_("events must contain between 1 and {0} items").format(MAX_BATCH_SIZE))

    results = []
    for event in events:
        savepoint = f"attendance_event_{len(results)}"
        frappe.db.savepoint(savepoint)
        try:
            results.append(_ingest_event(device, event))
        except Exception:
            frappe.db.rollback(save_point=savepoint)
            frappe.log_error(frappe.get_traceback(), "Face Attendance event ingestion")
            results.append({
                "event_id": event.get("event_id") if isinstance(event, dict) else None,
                "status": "rejected",
                "reason": "invalid_event",
            })

    _advance_contiguous_sequence(device)
    device.db_set("last_seen", now_datetime(), update_modified=False)
    return {"results": results, **_lease()}


def _parse_payload(raw_body):
    payload = _parse_json_body(raw_body)
    if payload.get("schema_version") != 1:
        frappe.throw(_("Unsupported schema version"))
    return payload


def _parse_json_body(raw_body):
    try:
        payload = json.loads(raw_body)
    except (TypeError, ValueError):
        frappe.throw(_("Request body must be valid JSON"))
    if not isinstance(payload, dict):
        frappe.throw(_("Request body must be a JSON object"))
    return payload


def _token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def _lease():
    server_time = int(time.time() * 1000)
    return {
        "server_time_epoch_millis": server_time,
        "authorization_expires_at_epoch_millis": server_time + AUTHORIZATION_LEASE_SECONDS * 1000,
    }


def _validate_public_key(public_key_base64):
    try:
        public_key = serialization.load_der_public_key(base64.b64decode(public_key_base64, validate=True))
    except (TypeError, ValueError):
        frappe.throw(_("Invalid public key"))
    if not isinstance(public_key, ec.EllipticCurvePublicKey) or public_key.curve.name != "secp256r1":
        frappe.throw(_("Public key must use the P-256 elliptic curve"))


def _authenticated_device(device_id):
    if frappe.session.user == "Guest":
        frappe.throw(_("Authentication required"), frappe.AuthenticationError)
    name = frappe.db.get_value(
        "Attendance Device",
        {"device_id": device_id, "device_user": frappe.session.user},
        "name",
    )
    if not name:
        frappe.throw(_("Device identity does not match authenticated user"), frappe.PermissionError)
    device = frappe.get_doc("Attendance Device", name)
    if not device.enabled or not device.public_key:
        frappe.throw(_("Device is disabled or not activated"), frappe.PermissionError)
    return device


def _verify_signature(public_key_base64, raw_body, signature_base64):
    if not signature_base64:
        frappe.throw(_("Device signature is required"), frappe.AuthenticationError)
    try:
        public_key = serialization.load_der_public_key(base64.b64decode(public_key_base64, validate=True))
        if not isinstance(public_key, ec.EllipticCurvePublicKey):
            raise ValueError("Unexpected key type")
        public_key.verify(
            base64.b64decode(signature_base64, validate=True),
            raw_body,
            ec.ECDSA(hashes.SHA256()),
        )
    except (InvalidSignature, TypeError, ValueError):
        frappe.throw(_("Invalid device signature"), frappe.AuthenticationError)


def _ingest_event(device, event):
    if not isinstance(event, dict) or REQUIRED_EVENT_FIELDS - event.keys():
        raise ValueError("Missing required event fields")
    event_id = str(event["event_id"])
    payload_hash = hashlib.sha256(
        json.dumps(event, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    existing = frappe.db.get_value(
        "Face Attendance Event", event_id, ["payload_hash", "disposition"], as_dict=True
    )
    if existing:
        if existing.payload_hash != payload_hash:
            return {"event_id": event_id, "status": "rejected", "reason": "event_id_reused"}
        return {"event_id": event_id, "status": "duplicate"}

    _validate_event(device, event)
    employee = frappe.db.get_value("Employee", {"biometric_person_id": event["person_id"]}, "name")
    if not employee:
        return {"event_id": event_id, "status": "rejected", "reason": "unknown_person"}

    disposition = "Accepted"
    reason = None
    if event["timestamp_confidence"] != "TRUSTED":
        disposition, reason = "Quarantined", "untrusted_timestamp"
    elif str(event["assignment_version"]) != str(device.assignment_version):
        disposition, reason = "Quarantined", "stale_assignment"

    captured_at = _captured_at(event["captured_at"])
    doc = frappe.get_doc({
        "doctype": "Face Attendance Event",
        "event_id": event_id,
        "payload_hash": payload_hash,
        "device": device.name,
        "device_sequence": int(event["device_sequence"]),
        "employee": employee,
        "branch_id": event["branch_id"],
        "gate_id": event["gate_id"],
        "assignment_version": str(event["assignment_version"]),
        "event_type": event["event_type"],
        "captured_at": captured_at,
        "captured_at_iso": event["captured_at"],
        "received_at": now_datetime(),
        "boot_id": event["boot_id"],
        "captured_at_elapsed_ms": event.get("captured_at_elapsed_ms"),
        "timezone_offset_minutes": event.get("timezone_offset_minutes"),
        "timestamp_confidence": event["timestamp_confidence"],
        "match_score": float(event["match_score"]),
        "liveness_score": float(event["liveness_score"]),
        "model_version": event["model_version"],
        "template_version": event["template_version"],
        "roster_version": event["roster_version"],
        "disposition": disposition,
        "disposition_reason": reason,
    }).insert(ignore_permissions=True)

    if disposition == "Accepted" and _managed_attendance():
        _create_checkin(doc)
    return {
        "event_id": event_id,
        "status": "accepted" if disposition == "Accepted" else "quarantined",
        "reason": reason,
    }


def _validate_event(device, event):
    if event["device_id"] != device.device_id:
        raise ValueError("Device mismatch")
    if event["branch_id"] != device.branch_id or event["gate_id"] != device.gate_id:
        raise ValueError("Gate assignment mismatch")
    if event["event_type"] not in {"IN", "OUT"}:
        raise ValueError("Invalid event type")
    if int(event["device_sequence"]) <= 0:
        raise ValueError("Invalid device sequence")
    if not 0 <= float(event["match_score"]) <= 1 or not 0 <= float(event["liveness_score"]) <= 1:
        raise ValueError("Invalid biometric score")
    duplicate_sequence = frappe.db.exists(
        "Face Attendance Event",
        {"device": device.name, "device_sequence": int(event["device_sequence"])},
    )
    if duplicate_sequence:
        raise ValueError("Device sequence already used")


def _captured_at(value):
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("captured_at must include a timezone")
    return parsed.astimezone(ZoneInfo(get_system_timezone())).replace(tzinfo=None)


def _managed_attendance():
    authority = frappe.db.get_single_value("Face Attendance Settings", "attendance_authority")
    return authority != "External Authority"


def _create_checkin(event):
    existing = frappe.db.get_value(
        "Employee Checkin", {"face_attendance_event_id": event.event_id}, "name"
    )
    if existing:
        event.db_set("employee_checkin", existing, update_modified=False)
        return
    checkin = frappe.get_doc({
        "doctype": "Employee Checkin",
        "employee": event.employee,
        "time": event.captured_at,
        "log_type": event.event_type,
        "device_id": event.device,
        "face_attendance_event_id": event.event_id,
    }).insert(ignore_permissions=True)
    event.db_set("employee_checkin", checkin.name, update_modified=False)


def _advance_contiguous_sequence(device):
    sequence = int(device.last_received_sequence or 0)
    while frappe.db.exists(
        "Face Attendance Event", {"device": device.name, "device_sequence": sequence + 1}
    ):
        sequence += 1
    if sequence != int(device.last_received_sequence or 0):
        device.db_set("last_received_sequence", sequence, update_modified=False)
