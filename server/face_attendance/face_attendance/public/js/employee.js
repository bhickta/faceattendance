frappe.ui.form.on("Employee", {
  refresh(frm) {
    if (frm.is_new() || !frm.doc.biometric_person_id) return;

    frm.add_custom_button(
      __("Register Face from Photo"),
      () => register_face_from_photo(frm),
      __("Face Attendance"),
    );

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

function register_face_from_photo(frm) {
  const uploaded = [];

  const dialog = new frappe.ui.Dialog({
    title: __("Register Face from Photo"),
    size: "large",
    fields: [
      {
        fieldtype: "HTML",
        fieldname: "uploader",
        options: `<div class="text-muted small">${__(
          "Upload 1–10 clear, front-facing photographs of the employee. One photo is enough; three to five improve accuracy.",
        )}</div>`,
      },
      {
        fieldtype: "Data",
        fieldname: "branch_id",
        label: __("Branch"),
        description: __("Leave blank to distribute this employee to every device."),
      },
      {
        fieldtype: "Check",
        fieldname: "consent",
        label: __("Employee has consented to biometric enrollment"),
      },
    ],
    primary_action_label: __("Register Face"),
    primary_action(values) {
      if (!values.consent) {
        frappe.msgprint(__("Confirm employee consent before enrolling."));
        return;
      }
      if (!uploaded.length) {
        frappe.msgprint(__("Upload at least one photograph."));
        return;
      }
      frappe.call({
        method: "face_attendance.api.v1.register_face_from_photo",
        args: {
          employee: frm.doc.name,
          files: uploaded,
          branch_id: values.branch_id || "",
          consent_confirmed: true,
        },
        freeze: true,
        freeze_message: __("Generating biometric template…"),
      }).then(({ message }) => {
        dialog.hide();
        frappe.msgprint({
          title: __("Face registered"),
          indicator: "green",
          message: __(
            "Template <b>{0}</b> created from {1} photo(s). {2} enabled device(s) will pick it up on the next sync.",
            [
              frappe.utils.escape_html(message.template_version),
              message.samples,
              message.device_count,
            ],
          ),
        });
      });
    },
  });

  dialog.show();
  const wrapper = dialog.get_field("uploader").$wrapper;
  new frappe.ui.FileUploader({
    wrapper: wrapper,
    allow_multiple: true,
    allow_take_photo: true,
    restrictions: { allowed_file_types: ["image/*"] },
    on_success: (file_doc) => {
      uploaded.push(file_doc.name);
      wrapper.find(".fa-uploaded-summary").remove();
      wrapper.append(
        `<div class="fa-uploaded-summary text-success small" style="margin-top:8px">${__(
          "{0} photo(s) ready",
          [uploaded.length],
        )}</div>`,
      );
    },
  });
}
