import frappe


def reconcile_pending_checkins():
    if frappe.db.get_single_value("Face Attendance Settings", "attendance_authority") == "External Authority":
        return
    events = frappe.get_all(
        "Face Attendance Event",
        filters={"disposition": "Accepted", "employee_checkin": ["is", "not set"]},
        pluck="name",
        limit_page_length=500,
    )
    from face_attendance.api.v1 import _create_checkin

    for name in events:
        try:
            _create_checkin(frappe.get_doc("Face Attendance Event", name))
        except Exception:
            frappe.log_error(frappe.get_traceback(), f"Reconcile Face Attendance Event {name}")


def expire_provisioning_tokens():
    frappe.db.delete(
        "Device Provisioning Token",
        {"expires_at": ["<", frappe.utils.add_days(frappe.utils.now_datetime(), -7)]},
    )
    frappe.db.delete(
        "Biometric Enrollment Token",
        {"expires_at": ["<", frappe.utils.add_days(frappe.utils.now_datetime(), -7)]},
    )
