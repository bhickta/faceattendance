frappe.ui.form.on("Attendance Device", {
  refresh(frm) {
    if (frm.is_new() || !frm.doc.enabled) return;

    frm.add_custom_button(__("Issue Activation Token"), () => {
      frappe.call({
        method: "face_attendance.api.v1.create_provisioning_token",
        args: { device_id: frm.doc.name },
        freeze: true,
        freeze_message: __("Issuing one-time activation token…"),
      }).then(({ message }) => {
        const token = frappe.utils.escape_html(message.activation_token);
        frappe.msgprint({
          title: __("Kiosk activation token"),
          indicator: "green",
          message: __("Enter this token on the kiosk within 15 minutes:<br><br><code>{0}</code>", [token]),
        });
      });
    });
  },
});
