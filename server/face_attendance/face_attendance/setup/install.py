import frappe
from frappe.custom.doctype.custom_field.custom_field import create_custom_fields

CUSTOM_FIELDS = {
    "Employee": [
        {
            "fieldname": "biometric_person_id",
            "label": "Biometric Person ID",
            "fieldtype": "Data",
            "unique": 1,
            "no_copy": 1,
            "description": "Stable identifier used by Face Attendance devices.",
        },
        {
            "fieldname": "face_attendance_pending_approval",
            "label": "Face Registration Pending Approval",
            "fieldtype": "Check",
            "read_only": 1,
            "no_copy": 1,
            "description": "Set for a temporary employee created by a kiosk self-registration.",
        },
        {
            "fieldname": "face_attendance_registration",
            "label": "Face Attendance Registration",
            "fieldtype": "Link",
            "options": "Face Attendance Registration",
            "read_only": 1,
            "no_copy": 1,
        },
    ],
    "Employee Checkin": [
        {
            "fieldname": "face_attendance_event_id",
            "label": "Face Attendance Event ID",
            "fieldtype": "Data",
            "unique": 1,
            "read_only": 1,
            "no_copy": 1,
        }
    ],
}


def after_install():
    create_custom_fields(CUSTOM_FIELDS, update=True)
    _add_indexes()


def after_migrate():
    create_custom_fields(CUSTOM_FIELDS, update=True)
    _add_indexes()
    _migrate_branch_assignments()
    _migrate_device_last_sync()


def _migrate_device_last_sync():
    """Carry over the old last_seen column after the rename to last_sync_at.

    last_seen was silently dropped from every list query because Frappe's
    optional_fields check matches the '_seen' substring.
    """
    if not frappe.db.table_exists("Attendance Device"):
        return
    if not frappe.db.has_column("Attendance Device", "last_sync_at"):
        return
    if not frappe.db.has_column("Attendance Device", "last_seen"):
        return
    frappe.db.sql(
        "update `tabAttendance Device` set last_sync_at = coalesce(last_sync_at, last_seen)"
    )


def _migrate_branch_assignments():
    """Move legacy single branch_id values onto the Allowed Branch child table."""
    if not frappe.db.table_exists("Biometric Template"):
        return
    if not frappe.db.table_exists("Allowed Branch"):
        return
    rows = frappe.get_all(
        "Biometric Template",
        filters={"branch_id": ["is", "set"]},
        fields=["name", "branch_id"],
    )
    for row in rows:
        branch = (row.branch_id or "").strip()
        if not branch:
            continue
        if frappe.db.exists("Allowed Branch", {"parent": row.name, "branch": branch}):
            continue
        if not frappe.db.exists("Branch", branch):
            frappe.get_doc({"doctype": "Branch", "branch": branch}).insert(ignore_permissions=True)
        template = frappe.get_doc("Biometric Template", row.name)
        template.append("allowed_branches", {"branch": branch})
        template.branch_id = ""
        template.save(ignore_permissions=True)


def _add_indexes():
    if frappe.db.table_exists("Face Attendance Event"):
        frappe.db.add_unique(
            "Face Attendance Event",
            ["device", "device_sequence"],
            constraint_name="unique_device_sequence",
        )
    if frappe.db.table_exists("Attendance Delivery"):
        frappe.db.add_unique(
            "Attendance Delivery",
            ["event", "destination"],
            constraint_name="unique_event_destination",
        )
    if frappe.db.table_exists("Biometric Template"):
        frappe.db.add_unique(
            "Biometric Template",
            ["employee", "model_version"],
            constraint_name="unique_employee_biometric_model",
        )
