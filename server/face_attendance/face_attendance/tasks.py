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


def purge_expired_events(limit=2000):
    """Delete events older than the configured retention while keeping check ins."""
    retention_days = int(
        frappe.db.get_single_value("Face Attendance Settings", "event_retention_days") or 0
    )
    if retention_days <= 0:
        return 0
    cutoff = frappe.utils.add_days(frappe.utils.now_datetime(), -retention_days)
    names = frappe.get_all(
        "Face Attendance Event",
        filters={"creation": ["<", cutoff]},
        pluck="name",
        limit_page_length=limit,
    )
    if not names:
        return 0

    frappe.db.delete("Attendance Delivery", {"event": ["in", names]})
    frappe.db.sql(
        """update `tabEmployee Checkin`
        set face_attendance_event_id = null
        where face_attendance_event_id in %(names)s""",
        {"names": names},
    )
    frappe.flags.face_attendance_maintenance = True
    try:
        for name in names:
            frappe.delete_doc(
                "Face Attendance Event",
                name,
                ignore_permissions=True,
                force=True,
            )
        frappe.db.commit()
    finally:
        frappe.flags.face_attendance_maintenance = False
    return len(names)

