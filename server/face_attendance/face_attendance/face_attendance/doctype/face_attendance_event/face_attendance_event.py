from frappe.model.document import Document


class FaceAttendanceEvent(Document):
    def autoname(self):
        self.name = self.event_id

    def validate(self):
        if not self.is_new():
            self._validate_immutable_fields()

    def _validate_immutable_fields(self):
        previous = self.get_doc_before_save()
        if not previous:
            return
        mutable = {"disposition", "disposition_reason", "employee_checkin", "modified", "modified_by"}
        for field in self.meta.get_valid_columns():
            if field not in mutable and previous.get(field) != self.get(field):
                raise ValueError(f"Attendance evidence field {field} is immutable")
