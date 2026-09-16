frappe.ui.form.on("Employee", {
  refresh(frm) {
    if (frm.is_new() || !frm.doc.biometric_person_id) return;

    frm.add_custom_button(
      __("Issue Face Enrollment Token"),
      () => {
        frappe.confirm(
          __("Confirm that this employee has consented to biometric enrollment."),
          () => {
            frappe.call({
              method: "face_attendance.api.v1.create_enrollment_token",
              args: {
                employee: frm.doc.name,
                consent_confirmed: true,
              },
              freeze: true,
              freeze_message: __("Issuing one-time enrollment token…"),
            }).then(({ message }) => {
              const token = frappe.utils.escape_html(message.enrollment_token);
              frappe.msgprint({
                title: __("Enrollment token"),
                indicator: "green",
                message: __("Enter this token on the kiosk within 15 minutes:<br><br><code>{0}</code>", [token]),
              });
            });
          },
        );
      },
      __("Face Attendance"),
    );
  },
});
