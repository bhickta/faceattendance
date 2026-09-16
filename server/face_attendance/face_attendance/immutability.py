import frappe
from frappe import _

MAINTAINER_ROLE = "System Manager"


def reject_mutation(doc, method=None):
    if getattr(frappe.flags, "face_attendance_maintenance", False):
        return
    if MAINTAINER_ROLE in frappe.get_roles(frappe.session.user):
        frappe.logger("face_attendance").warning(
            "Attendance evidence %s %s by %s",
            doc.name,
            method or "delete",
            frappe.session.user,
        )
        return
    frappe.throw(_("Attendance evidence is immutable"), frappe.PermissionError)
