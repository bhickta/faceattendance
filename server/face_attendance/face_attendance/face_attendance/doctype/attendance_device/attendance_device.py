import frappe
from frappe.model.document import Document


class AttendanceDevice(Document):
    def autoname(self):
        self.name = self.device_id

    def validate(self):
        if self.device_user == "Administrator" or not self.device_user:
            frappe.throw("A dedicated, non-Administrator device user is required")
