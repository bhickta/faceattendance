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
        }
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
