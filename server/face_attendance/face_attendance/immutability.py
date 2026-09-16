import frappe
from frappe import _


def reject_mutation(doc, method=None):
    if not getattr(frappe.flags, "face_attendance_maintenance", False):
        frappe.throw(_("Attendance evidence is immutable"), frappe.PermissionError)
