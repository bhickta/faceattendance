from frappe.model.document import Document

from face_attendance.integrations.webhook import validate_public_https_url


class IntegrationDestination(Document):
    def validate(self):
        validate_public_https_url(self.endpoint_url)
        if not 1 <= int(self.max_attempts or 0) <= 50:
            raise ValueError("Max Attempts must be between 1 and 50")
