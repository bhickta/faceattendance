import frappe
from frappe import _
from frappe.utils import now_datetime

HEALTHY_DEVICE_AGE_MINUTES = 60


def execute(filters=None):
    columns = [
        {
            "label": _("Device"),
            "fieldname": "name",
            "fieldtype": "Link",
            "options": "Attendance Device",
            "width": 170,
        },
        {"label": _("Branch"), "fieldname": "branch_id", "fieldtype": "Data", "width": 120},
        {"label": _("Gate"), "fieldname": "gate_id", "fieldtype": "Data", "width": 120},
        {"label": _("Mode"), "fieldname": "direction_mode", "fieldtype": "Data", "width": 100},
        {"label": _("Enabled"), "fieldname": "enabled", "fieldtype": "Check", "width": 80},
        {"label": _("Activated"), "fieldname": "activated", "fieldtype": "Check", "width": 90},
        {"label": _("Last Sync"), "fieldname": "last_sync_at", "fieldtype": "Datetime", "width": 170},
        {"label": _("Age (min)"), "fieldname": "age_minutes", "fieldtype": "Int", "width": 90},
        {"label": _("Healthy"), "fieldname": "healthy", "fieldtype": "Check", "width": 80},
        {
            "label": _("Last Sequence"),
            "fieldname": "last_received_sequence",
            "fieldtype": "Int",
            "width": 120,
        },
    ]
    now = now_datetime()
    rows = frappe.get_all(
        "Attendance Device",
        fields=[
            "name",
            "branch_id",
            "gate_id",
            "direction_mode",
            "enabled",
            "public_key",
            "last_sync_at",
            "last_received_sequence",
        ],
        order_by="name asc",
        limit_page_length=0,
    )
    for row in rows:
        age = None if not row.last_sync_at else int((now - row.last_sync_at).total_seconds() // 60)
        row["age_minutes"] = age
        row["activated"] = bool(row.pop("public_key"))
        row["healthy"] = bool(
            row.enabled and age is not None and age <= HEALTHY_DEVICE_AGE_MINUTES
        )
    return columns, rows
