frappe.listview_settings["Face Attendance Registration"] = {
  add_fields: ["status", "employee"],
  get_indicator(doc) {
    if (doc.status === "Pending") {
      return [__("Pending"), "orange", "status,=,Pending"];
    }
    if (doc.status === "Approved") {
      return [__("Approved"), "green", "status,=,Approved"];
    }
    return [__("Rejected"), "red", "status,=,Rejected"];
  },
};
