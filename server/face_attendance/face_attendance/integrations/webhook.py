import hashlib
import hmac
import ipaddress
import json
import socket
import time
from datetime import timedelta
from urllib.parse import urlparse

import frappe
import requests
from frappe.utils import add_to_date, now_datetime

MAX_DELIVERIES_PER_RUN = 100
REQUEST_TIMEOUT_SECONDS = (5, 20)


def validate_public_https_url(value):
    parsed = urlparse(str(value))
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Destination must be a credential-free HTTPS URL")
    if parsed.port not in (None, 443):
        raise ValueError("Destination must use HTTPS port 443")
    addresses = {item[4][0] for item in socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)}
    if not addresses:
        raise ValueError("Destination hostname did not resolve")
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if not ip.is_global:
            raise ValueError("Destination must resolve only to public IP addresses")
    return parsed


def create_deliveries(doc, method=None):
    if doc.disposition != "Accepted":
        return
    destinations = frappe.get_all("Integration Destination", filters={"enabled": 1}, pluck="name")
    for destination in destinations:
        if frappe.db.exists("Attendance Delivery", {"event": doc.name, "destination": destination}):
            continue
        frappe.get_doc({
            "doctype": "Attendance Delivery",
            "event": doc.name,
            "destination": destination,
            "status": "Pending",
            "next_attempt_at": now_datetime(),
        }).insert(ignore_permissions=True)


def process_due_deliveries():
    _recover_stalled_deliveries()
    names = frappe.get_all(
        "Attendance Delivery",
        filters={
            "status": ["in", ["Pending", "Retry"]],
            "next_attempt_at": ["<=", now_datetime()],
        },
        order_by="next_attempt_at asc",
        pluck="name",
        limit_page_length=MAX_DELIVERIES_PER_RUN,
    )
    for name in names:
        frappe.enqueue(
            "face_attendance.integrations.webhook.deliver",
            queue="short",
            delivery_name=name,
            enqueue_after_commit=True,
        )


def deliver(delivery_name):
    claim = frappe.db.sql(
        """select name from `tabAttendance Delivery`
        where name = %s and status in ('Pending', 'Retry') for update""",
        (delivery_name,),
    )
    if not claim:
        return
    frappe.db.set_value("Attendance Delivery", delivery_name, "status", "Processing", update_modified=True)
    frappe.db.commit()

    delivery = frappe.get_doc("Attendance Delivery", delivery_name)
    destination = frappe.get_doc("Integration Destination", delivery.destination)
    if not destination.enabled:
        _finish(delivery, "Failed", error="destination_disabled")
        return
    try:
        validate_public_https_url(destination.endpoint_url)
        body = _payload(delivery.event)
        timestamp = str(int(time.time()))
        secret = destination.get_password("signing_secret").encode()
        signature = hmac.new(secret, timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
        response = requests.post(
            destination.endpoint_url,
            data=body,
            headers={
                "Content-Type": "application/json",
                "X-Face-Attendance-Timestamp": timestamp,
                "X-Face-Attendance-Signature": f"sha256={signature}",
                "Idempotency-Key": delivery.event,
            },
            timeout=REQUEST_TIMEOUT_SECONDS,
            allow_redirects=False,
        )
        if 200 <= response.status_code < 300:
            _finish(delivery, "Delivered", response_status=response.status_code)
        elif response.status_code == 429 or response.status_code >= 500:
            _retry(delivery, f"HTTP {response.status_code}", response.status_code)
        else:
            _finish(delivery, "Failed", f"HTTP {response.status_code}", response.status_code)
    except (requests.RequestException, OSError, ValueError) as error:
        _retry(delivery, str(error))


def _payload(event_name):
    event = frappe.get_doc("Face Attendance Event", event_name)
    person_id = frappe.db.get_value("Employee", event.employee, "biometric_person_id")
    return json.dumps(
        {
            "schema_version": 1,
            "event_id": event.event_id,
            "person_id": person_id,
            "employee_id": event.employee,
            "device_id": event.device,
            "branch_id": event.branch_id,
            "gate_id": event.gate_id,
            "event_type": event.event_type,
            "captured_at": event.captured_at_iso,
            "received_at": str(event.received_at),
            "timestamp_confidence": event.timestamp_confidence,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode()


def _retry(delivery, error, response_status=None):
    attempts = int(delivery.attempts or 0) + 1
    maximum = int(frappe.db.get_value("Integration Destination", delivery.destination, "max_attempts") or 20)
    if attempts >= maximum:
        _finish(delivery, "Failed", error, response_status, attempts)
        return
    delay_seconds = min(3600, 30 * (2 ** min(attempts - 1, 7)))
    _finish(
        delivery,
        "Retry",
        error,
        response_status,
        attempts,
        add_to_date(now_datetime(), seconds=delay_seconds),
    )


def _finish(
    delivery,
    status,
    error=None,
    response_status=None,
    attempts=None,
    next_attempt_at=None,
):
    values = {
        "status": status,
        "attempts": attempts if attempts is not None else int(delivery.attempts or 0) + 1,
        "last_attempt_at": now_datetime(),
        "last_error": str(error or "")[:500],
        "response_status": response_status,
        "next_attempt_at": next_attempt_at,
    }
    if status == "Delivered":
        values["delivered_at"] = now_datetime()
    frappe.db.set_value("Attendance Delivery", delivery.name, values, update_modified=True)


def _recover_stalled_deliveries():
    cutoff = now_datetime() - timedelta(minutes=10)
    frappe.db.sql(
        """update `tabAttendance Delivery`
        set status = 'Retry', next_attempt_at = %s
        where status = 'Processing' and modified < %s""",
        (now_datetime(), cutoff),
    )
