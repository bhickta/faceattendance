import base64
import math
import struct

import frappe
from frappe.model.document import Document

EMBEDDING_DIMENSIONS = 256


class FaceAttendanceRegistration(Document):
    def validate(self):
        if not self.embedding:
            return
        try:
            raw = base64.b64decode(self.embedding, validate=True)
            if len(raw) != EMBEDDING_DIMENSIONS * 4:
                raise ValueError("unexpected embedding length")
            values = struct.unpack(f"<{EMBEDDING_DIMENSIONS}f", raw)
        except (TypeError, ValueError, struct.error):
            frappe.throw("Embedding must contain 256 little-endian float32 values in base64")
        norm = math.sqrt(sum(value * value for value in values))
        if not all(math.isfinite(value) for value in values) or not 0.99 <= norm <= 1.01:
            frappe.throw("Embedding must be finite and L2-normalized")
