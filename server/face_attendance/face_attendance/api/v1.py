import base64
import hashlib
import json
import math
import secrets
import struct
import time
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import frappe
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from frappe import _
from frappe.utils import add_to_date, get_system_timezone, now_datetime, today

MAX_BATCH_SIZE = 100
MAXIMUM_ENROLLMENT_PHOTOS = 10
DUPLICATE_SIMILARITY_THRESHOLD = 0.72
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
BIOMETRIC_MODEL_VERSION = "intel-face-reidentification-retail-0095-onnx-v1"
BIOMETRIC_EMBEDDING_DIMENSIONS = 256


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


@frappe.whitelist(methods=["POST"])
def create_enrollment_token(employee, consent_confirmed=False, validity_minutes=15):
    frappe.only_for(["System Manager", "HR Manager"])
    if str(consent_confirmed).lower() not in {"1", "true", "yes"}:
        frappe.throw(_("Employee consent must be confirmed before enrollment"))
    validity_minutes = int(validity_minutes)
    if not 1 <= validity_minutes <= 60:
        frappe.throw(_("Validity must be between 1 and 60 minutes"))
    employee_doc = frappe.get_doc("Employee", employee)
    if not employee_doc.biometric_person_id:
        frappe.throw(_("Employee must have a Biometric Person ID"))
    token = secrets.token_urlsafe(32)
    frappe.get_doc({
        "doctype": "Biometric Enrollment Token",
        "employee": employee_doc.name,
        "token_hash": _token_hash(token),
        "expires_at": add_to_date(now_datetime(), minutes=validity_minutes),
        "issued_by": frappe.session.user,
        "consent_recorded_at": now_datetime(),
    }).insert()
    return {
        "enrollment_token": token,
        "employee": employee_doc.name,
        "employee_name": employee_doc.employee_name,
        "expires_in_seconds": validity_minutes * 60,
    }


def _require_consent(consent_confirmed):
    if str(consent_confirmed).lower() not in {"1", "true", "yes"}:
        frappe.throw(_("Employee consent must be confirmed before enrollment"))


def _load_vision():
    try:
        from face_attendance import vision
    except ImportError:
        frappe.throw(_("Photo enrollment requires the opencv-python-headless package on the server"))
    return vision


def _parse_base64_images(images):
    if images is None:
        return []
    if isinstance(images, str):
        try:
            images = json.loads(images)
        except ValueError:
            images = [images]
    if not isinstance(images, list | tuple):
        frappe.throw(_("images must be a list of base64 photographs"))
    cleaned = []
    for value in images:
        value = str(value).strip()
        if value.startswith("data:") and "," in value:
            value = value.split(",", 1)[1]
        if value:
            cleaned.append(value)
    return cleaned


def _collect_images(files, images):
    collected = []
    for name in _parse_file_names(files):
        if not frappe.db.exists("File", name):
            frappe.throw(_("Uploaded file {0} was not found").format(name))
        collected.append(frappe.get_doc("File", name).get_content())
    for index, encoded in enumerate(_parse_base64_images(images)):
        try:
            collected.append(base64.b64decode(encoded, validate=True))
        except (TypeError, ValueError):
            frappe.throw(_("Photo {0} could not be decoded").format(index + 1))
    return collected


def _parse_branches(branches, branch_id):
    raw = []
    if branches is not None:
        if isinstance(branches, str):
            try:
                branches = json.loads(branches)
            except ValueError:
                branches = branches.split(",")
        if isinstance(branches, (list | tuple)):
            for item in branches:
                value = item.get("branch") if isinstance(item, dict) else item
                raw.append(str(value).strip() if value else "")
        else:
            raw.append(str(branches).strip())
    if branch_id:
        raw.append(str(branch_id).strip())
    unique = []
    for value in raw:
        if value and value not in unique:
            unique.append(value)
    return unique


def _ensure_branch(name):
    name = (name or "").strip()
    if not name:
        return None
    if not frappe.db.exists("Branch", name):
        frappe.get_doc({"doctype": "Branch", "branch": name}).insert(ignore_permissions=True)
    return name


def _profile_picture_images(employee_doc):
    if not employee_doc.image:
        frappe.throw(_("Employee has no profile picture"))
    file_name = frappe.db.get_value("File", {"file_url": employee_doc.image}, "name")
    if not file_name:
        frappe.throw(_("Profile picture file was not found"))
    return [frappe.get_doc("File", file_name).get_content()]


def _register_face(employee_doc, images, branches):
    vision = _load_vision()
    if not employee_doc.biometric_person_id:
        frappe.throw(_("Employee must have a Biometric Person ID"))
    if not images:
        frappe.throw(_("Provide at least one photograph"))
    if len(images) > MAXIMUM_ENROLLMENT_PHOTOS:
        frappe.throw(_("Provide at most {0} photographs").format(MAXIMUM_ENROLLMENT_PHOTOS))

    result = vision.build_template(images)
    embedding = vision.decode_embedding(result["embedding"])

    for candidate in frappe.get_all(
        "Biometric Template",
        filters={
            "enabled": 1,
            "model_version": vision.MODEL_VERSION,
            "employee": ["!=", employee_doc.name],
        },
        fields=["employee", "embedding"],
        limit_page_length=100000,
    ):
        try:
            existing_values = vision.decode_embedding(candidate.embedding)
        except (TypeError, ValueError):
            continue
        if vision.cosine_similarity(embedding, existing_values) >= DUPLICATE_SIMILARITY_THRESHOLD:
            frappe.throw(
                _("Face appears to be enrolled already for employee {0}").format(candidate.employee)
            )

    allowed = [branch for branch in (_ensure_branch(name) for name in branches) if branch]

    existing = frappe.db.get_value(
        "Biometric Template",
        {"employee": employee_doc.name, "model_version": vision.MODEL_VERSION},
        "name",
    )
    template = frappe.get_doc("Biometric Template", existing) if existing else frappe.new_doc(
        "Biometric Template"
    )
    template.employee = employee_doc.name
    template.enabled = 1
    template.branch_id = ""
    template.model_version = vision.MODEL_VERSION
    template.template_version = result["template_version"]
    template.embedding = result["embedding"]
    template.consent_recorded_at = now_datetime()
    template.set("allowed_branches", [])
    for branch in allowed:
        template.append("allowed_branches", {"branch": branch})
    template.save(ignore_permissions=True)

    device_filters = {"enabled": 1}
    if allowed:
        device_filters["branch_id"] = ["in", allowed]
    return {
        "employee": employee_doc.name,
        "employee_name": employee_doc.employee_name,
        "model_version": vision.MODEL_VERSION,
        "template_version": result["template_version"],
        "samples": result["samples"],
        "branches": allowed,
        "device_count": frappe.db.count("Attendance Device", device_filters),
    }


@frappe.whitelist(methods=["POST"])
def register_face_from_photo(
    employee,
    files=None,
    images=None,
    branches=None,
    branch_id=None,
    consent_confirmed=False,
):
    """Create or replace an employee template from uploaded photographs."""
    frappe.only_for(["System Manager", "HR Manager"])
    _require_consent(consent_confirmed)
    employee_doc = frappe.get_doc("Employee", employee)
    return _register_face(
        employee_doc,
        _collect_images(files, images),
        _parse_branches(branches, branch_id),
    )


@frappe.whitelist(methods=["POST"])
def register_face_from_profile_picture(
    employee, branches=None, branch_id=None, consent_confirmed=False
):
    """Create or replace an employee template from the Employee profile picture."""
    frappe.only_for(["System Manager", "HR Manager"])
    _require_consent(consent_confirmed)
    employee_doc = frappe.get_doc("Employee", employee)
    return _register_face(
        employee_doc,
        _profile_picture_images(employee_doc),
        _parse_branches(branches, branch_id),
    )


@frappe.whitelist(methods=["POST"])
def register_my_face_from_photo(files=None, images=None, consent_confirmed=False):
    """Portal self-registration for the signed-in employee."""
    user = frappe.session.user
    if not user or user == "Guest":
        frappe.throw(_("Sign in to register your face"), frappe.AuthenticationError)
    if not frappe.db.get_single_value("Face Attendance Settings", "allow_self_registration"):
        frappe.throw(_("Self-registration is disabled. Contact your HR team."))
    _require_consent(consent_confirmed)
    employee = frappe.db.get_value("Employee", {"user_id": user, "status": "Active"}, "name")
    if not employee:
        frappe.throw(_("No active employee is linked to your account"))
    employee_doc = frappe.get_doc("Employee", employee)
    return _register_face(employee_doc, _collect_images(files, images), [])


@frappe.whitelist()
def my_face_status():
    user = frappe.session.user
    if not user or user == "Guest":
        return {"signed_in": False, "self_registration_enabled": False}
    employee = frappe.db.get_value("Employee", {"user_id": user, "status": "Active"}, "name")
    return {
        "signed_in": True,
        "employee": employee,
        "employee_name": frappe.db.get_value("Employee", employee, "employee_name")
        if employee
        else None,
        "registered": bool(
            employee and frappe.db.exists("Biometric Template", {"employee": employee, "enabled": 1})
        ),
        "self_registration_enabled": bool(
            frappe.db.get_single_value("Face Attendance Settings", "allow_self_registration")
        ),
    }


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
def sync_roster():
    raw_body = frappe.request.get_data(cache=True)
    payload = _parse_payload(raw_body)
    device = _authenticated_device(payload.get("device_id"))
    _verify_signature(device.public_key, raw_body, frappe.get_request_header("X-Device-Signature"))
    rows = frappe.db.sql(
        """select bt.name, bt.modified, bt.template_version, bt.embedding,
                  e.biometric_person_id, e.employee_name
           from `tabBiometric Template` bt
           inner join `tabEmployee` e on e.name = bt.employee
            where bt.enabled = 1 and bt.model_version = %s
              and coalesce(e.biometric_person_id, '') != ''
              and (
                not exists (select 1 from `tabAllowed Branch` ab where ab.parent = bt.name)
                or exists (
                  select 1 from `tabAllowed Branch` ab
                  where ab.parent = bt.name and ab.branch = %s
                )
              )
            order by bt.name""",
        (BIOMETRIC_MODEL_VERSION, device.branch_id),
        as_dict=True,
    )
    version_material = "\n".join(
        f"{row.name}|{row.modified}|{row.template_version}|{row.biometric_person_id}|{row.employee_name}"
        for row in rows
    )
    roster_version = hashlib.sha256(
        f"{BIOMETRIC_MODEL_VERSION}\n{version_material}".encode()
    ).hexdigest()
    response = {
        "changed": payload.get("roster_version") != roster_version,
        "roster_version": roster_version,
        "model_version": BIOMETRIC_MODEL_VERSION,
        **_lease(),
    }
    if response["changed"]:
        response["templates"] = [
            {
                "person_id": row.biometric_person_id,
                "display_name": row.employee_name,
                "template_version": row.template_version,
                "embedding": row.embedding,
            }
            for row in rows
        ]
    device.db_set("last_seen", now_datetime(), update_modified=False)
    return response


@frappe.whitelist(methods=["POST"])
def submit_enrollment():
    raw_body = frappe.request.get_data(cache=True)
    payload = _parse_payload(raw_body)
    device = _authenticated_device(payload.get("device_id"))
    _verify_signature(device.public_key, raw_body, frappe.get_request_header("X-Device-Signature"))
    if payload.get("model_version") != BIOMETRIC_MODEL_VERSION:
        frappe.throw(_("Enrollment model version is not supported"))
    token = str(payload.get("enrollment_token") or "")
    embedding, raw_embedding, embedding_values = _validated_embedding(payload.get("embedding"))
    rows = frappe.db.sql(
        """select name from `tabBiometric Enrollment Token`
           where token_hash = %s and used_at is null limit 1 for update""",
        (_token_hash(token),),
    )
    if not rows:
        frappe.throw(_("Invalid or already used enrollment token"), frappe.AuthenticationError)
    token_doc = frappe.get_doc("Biometric Enrollment Token", rows[0][0])
    if token_doc.expires_at < now_datetime():
        frappe.throw(_("Enrollment token has expired"), frappe.AuthenticationError)

    possible_duplicates = frappe.get_all(
        "Biometric Template",
        filters={
            "enabled": 1,
            "model_version": BIOMETRIC_MODEL_VERSION,
            "employee": ["!=", token_doc.employee],
        },
        fields=["employee", "embedding"],
        limit_page_length=100000,
    )
    for candidate in possible_duplicates:
        _, _, candidate_values = _validated_embedding(candidate.embedding)
        similarity = sum(
            left * right for left, right in zip(embedding_values, candidate_values, strict=True)
        )
        if similarity >= 0.72:
            frappe.throw(
                _("Face appears to be enrolled already for employee {0}").format(candidate.employee)
            )

    template_version = hashlib.sha256(raw_embedding).hexdigest()[:16]
    existing = frappe.db.get_value(
        "Biometric Template",
        {"employee": token_doc.employee, "model_version": BIOMETRIC_MODEL_VERSION},
        "name",
    )
    template = frappe.get_doc("Biometric Template", existing) if existing else frappe.new_doc(
        "Biometric Template"
    )
    template.employee = token_doc.employee
    template.enabled = 1
    template.branch_id = ""
    template.model_version = BIOMETRIC_MODEL_VERSION
    template.template_version = template_version
    template.embedding = embedding
    template.consent_recorded_at = token_doc.consent_recorded_at
    template.set("allowed_branches", [])
    branch = _ensure_branch(device.branch_id)
    if branch:
        template.append("allowed_branches", {"branch": branch})
    template.save(ignore_permissions=True)
    token_doc.db_set("used_at", now_datetime(), update_modified=False)
    return {
        "employee": token_doc.employee,
        "template_version": template_version,
        "roster_refresh_required": True,
    }


@frappe.whitelist(methods=["POST"])
def submit_self_registration():
    """Kiosk side self-registration of an unknown person for HR approval."""
    raw_body = frappe.request.get_data(cache=True)
    payload = _parse_payload(raw_body)
    device = _authenticated_device(payload.get("device_id"))
    _verify_signature(device.public_key, raw_body, frappe.get_request_header("X-Device-Signature"))

    full_name = str(payload.get("full_name") or "").strip()
    if not 2 <= len(full_name) <= 140:
        frappe.throw(_("A name between 2 and 140 characters is required"))
    if str(payload.get("consent_confirmed")).lower() not in {"1", "true", "yes"}:
        frappe.throw(_("Consent is required before registration"))
    if payload.get("model_version") != BIOMETRIC_MODEL_VERSION:
        frappe.throw(_("Registration model version is not supported"))

    embedding, raw_embedding, values = _validated_embedding(payload.get("embedding"))
    captured_at = (
        _captured_at(payload["captured_at"]) if payload.get("captured_at") else now_datetime()
    )

    for candidate in frappe.get_all(
        "Biometric Template",
        filters={"enabled": 1, "model_version": BIOMETRIC_MODEL_VERSION},
        fields=["employee", "embedding"],
        limit_page_length=100000,
    ):
        try:
            _, _, candidate_values = _validated_embedding(candidate.embedding)
        except frappe.ValidationError:
            continue
        similarity = sum(
            left * right for left, right in zip(values, candidate_values, strict=True)
        )
        if similarity >= DUPLICATE_SIMILARITY_THRESHOLD:
            return {
                "status": "already_enrolled",
                "employee": candidate.employee,
                "employee_name": frappe.db.get_value("Employee", candidate.employee, "employee_name"),
            }

    for candidate in frappe.get_all(
        "Face Attendance Registration",
        filters={"status": "Pending"},
        fields=["name", "full_name", "embedding"],
        limit_page_length=100000,
    ):
        if not candidate.embedding:
            continue
        try:
            _, _, candidate_values = _validated_embedding(candidate.embedding)
        except frappe.ValidationError:
            continue
        similarity = sum(
            left * right for left, right in zip(values, candidate_values, strict=True)
        )
        if similarity >= DUPLICATE_SIMILARITY_THRESHOLD:
            return {
                "status": "pending_approval",
                "registration": candidate.name,
                "employee_name": candidate.full_name,
            }

    registration = frappe.get_doc({
        "doctype": "Face Attendance Registration",
        "full_name": full_name,
        "phone": str(payload.get("phone") or "").strip()[:40],
        "status": "Pending",
        "requested_direction": "OUT" if str(payload.get("direction")).upper() == "OUT" else "IN",
        "captured_at": captured_at,
        "device": device.name,
        "branch_id": device.branch_id,
        "gate_id": device.gate_id,
        "consent_recorded_at": now_datetime(),
        "model_version": BIOMETRIC_MODEL_VERSION,
        "template_version": hashlib.sha256(raw_embedding).hexdigest()[:16],
        "sample_count": int(payload.get("sample_count") or 0),
        "embedding": embedding,
    }).insert(ignore_permissions=True)
    device.db_set("last_seen", now_datetime(), update_modified=False)
    return {"status": "pending_approval", "registration": registration.name}


@frappe.whitelist(methods=["POST"])
def approve_registration(
    name,
    employee=None,
    company=None,
    gender=None,
    date_of_birth=None,
    date_of_joining=None,
    biometric_person_id=None,
):
    """Turn a pending kiosk registration into an employee with a face template."""
    frappe.only_for(["System Manager", "HR Manager"])
    registration = frappe.get_doc("Face Attendance Registration", name)
    if registration.status != "Pending":
        frappe.throw(_("This registration has already been reviewed"))

    if employee:
        employee_doc = frappe.get_doc("Employee", employee)
    else:
        employee_doc = _create_employee_from_registration(
            registration,
            company,
            gender,
            date_of_birth,
            date_of_joining,
            biometric_person_id,
        )

    existing = frappe.db.get_value(
        "Biometric Template",
        {"employee": employee_doc.name, "model_version": registration.model_version},
        "name",
    )
    template = frappe.get_doc("Biometric Template", existing) if existing else frappe.new_doc(
        "Biometric Template"
    )
    template.employee = employee_doc.name
    template.enabled = 1
    template.branch_id = ""
    template.model_version = registration.model_version
    template.template_version = registration.template_version
    template.embedding = registration.embedding
    template.consent_recorded_at = registration.consent_recorded_at or now_datetime()
    template.set("allowed_branches", [])
    template.save(ignore_permissions=True)

    if _managed_attendance() and registration.captured_at:
        frappe.get_doc({
            "doctype": "Employee Checkin",
            "employee": employee_doc.name,
            "time": registration.captured_at,
            "log_type": registration.requested_direction or "IN",
            "device_id": registration.device,
        }).insert(ignore_permissions=True)

    registration.db_set("status", "Approved")
    registration.db_set("employee", employee_doc.name)
    registration.db_set("reviewed_by", frappe.session.user)
    registration.db_set("reviewed_at", now_datetime())
    return {
        "status": "Approved",
        "employee": employee_doc.name,
        "employee_name": employee_doc.employee_name,
    }


@frappe.whitelist(methods=["POST"])
def reject_registration(name, reason=None):
    frappe.only_for(["System Manager", "HR Manager"])
    registration = frappe.get_doc("Face Attendance Registration", name)
    if registration.status != "Pending":
        frappe.throw(_("This registration has already been reviewed"))
    registration.db_set("status", "Rejected")
    registration.db_set("rejection_reason", (reason or "").strip())
    registration.db_set("reviewed_by", frappe.session.user)
    registration.db_set("reviewed_at", now_datetime())
    return {"status": "Rejected"}


def _create_employee_from_registration(
    registration,
    company,
    gender,
    date_of_birth,
    date_of_joining,
    biometric_person_id,
):
    company = (
        company
        or frappe.defaults.get_global_default("company")
        or frappe.db.get_value("Company", {}, "name")
    )
    if not company:
        frappe.throw(_("Set a default Company before approving registrations"))
    person_id = (biometric_person_id or "").strip() or registration.name
    if frappe.db.exists("Employee", {"biometric_person_id": person_id}):
        frappe.throw(_("Biometric Person ID {0} is already in use").format(person_id))
    return frappe.get_doc({
        "doctype": "Employee",
        "first_name": registration.full_name,
        "employee_name": registration.full_name,
        "gender": gender or "Prefer not to say",
        "date_of_birth": date_of_birth or "1990-01-01",
        "date_of_joining": date_of_joining or today(),
        "status": "Active",
        "company": company,
        "cell_number": registration.phone or None,
        "biometric_person_id": person_id,
    }).insert(ignore_permissions=True)


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


def _parse_file_names(files):
    if isinstance(files, str):
        try:
            files = json.loads(files)
        except ValueError:
            files = files.split(",")
    if not isinstance(files, list | tuple):
        frappe.throw(_("files must be a list of uploaded file names"))
    return [str(name).strip() for name in files if str(name).strip()]


def _validated_embedding(encoded):
    try:
        raw = base64.b64decode(str(encoded), validate=True)
        if len(raw) != BIOMETRIC_EMBEDDING_DIMENSIONS * 4:
            raise ValueError("unexpected embedding length")
        values = struct.unpack(f"<{BIOMETRIC_EMBEDDING_DIMENSIONS}f", raw)
    except (TypeError, ValueError, struct.error):
        frappe.throw(_("Embedding is invalid"))
    norm = math.sqrt(sum(value * value for value in values))
    if not all(math.isfinite(value) for value in values) or not 0.99 <= norm <= 1.01:
        frappe.throw(_("Embedding must be finite and L2-normalized"))
    return str(encoded), raw, values


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

    captured_at = _captured_at(event["captured_at"])
    disposition, reason = _disposition(device, event, captured_at)
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
    event_id = str(event["event_id"])
    if str(uuid.UUID(event_id)) != event_id.lower():
        raise ValueError("event_id must be a canonical UUID")
    if event["device_id"] != device.device_id:
        raise ValueError("Device mismatch")
    current_assignment = str(event["assignment_version"]) == str(device.assignment_version)
    if current_assignment and (
        event["branch_id"] != device.branch_id or event["gate_id"] != device.gate_id
    ):
        raise ValueError("Gate assignment mismatch")
    if event["event_type"] not in {"IN", "OUT"}:
        raise ValueError("Invalid event type")
    if int(event["device_sequence"]) <= 0:
        raise ValueError("Invalid device sequence")
    if not 0 <= float(event["match_score"]) <= 1 or not 0 <= float(event["liveness_score"]) <= 1:
        raise ValueError("Invalid biometric score")
    for field in ("person_id", "branch_id", "gate_id", "assignment_version", "boot_id"):
        if not str(event[field]).strip() or len(str(event[field])) > 140:
            raise ValueError(f"Invalid {field}")
    for field in ("model_version", "template_version", "roster_version"):
        if not str(event[field]).strip() or len(str(event[field])) > 140:
            raise ValueError(f"Invalid {field}")
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


def _disposition(device, event, captured_at):
    now = now_datetime()
    if event["timestamp_confidence"] != "TRUSTED":
        return "Quarantined", "untrusted_timestamp"
    if captured_at > now + timedelta(minutes=5):
        return "Quarantined", "future_timestamp"
    if captured_at < now - timedelta(days=8):
        return "Quarantined", "outside_offline_window"
    if str(event["assignment_version"]) != str(device.assignment_version):
        return "Quarantined", "stale_assignment"
    return "Accepted", None


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
