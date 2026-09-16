frappe.ui.form.on("Face Attendance Registration", {
  refresh(frm) {
    if (frm.doc.status !== "Pending") {
      frm.dashboard.set_headline(
        __("Reviewed by {0}", [frm.doc.reviewed_by || __("unknown")]),
      );
      return;
    }

    frm.add_custom_button(__("Approve as Employee"), () => approve_dialog(frm));
    frm.add_custom_button(__("Reject"), () => reject_dialog(frm));
    frm.page.set_primary_action(__("Approve as Employee"), () => approve_dialog(frm));
  },
});

function approve_dialog(frm) {
  const dialog = new frappe.ui.Dialog({
    title: __("Approve registration"),
    fields: [
      {
        fieldtype: "Link",
        fieldname: "company",
        label: __("Company"),
        options: "Company",
        reqd: 1,
        default: frappe.defaults.get_default("company"),
      },
      {
        fieldtype: "Link",
        fieldname: "gender",
        label: __("Gender"),
        options: "Gender",
        default: "Prefer not to say",
      },
      { fieldtype: "Date", fieldname: "date_of_birth", label: __("Date of Birth") },
      {
        fieldtype: "Date",
        fieldname: "date_of_joining",
        label: __("Date of Joining"),
        default: frappe.datetime.get_today(),
      },
      {
        fieldtype: "Data",
        fieldname: "biometric_person_id",
        label: __("Biometric Person ID"),
        default: frm.doc.name,
        description: __("Stable identifier used by the kiosks. Keep the default unless you have a policy."),
      },
    ],
    primary_action_label: __("Approve"),
    primary_action(values) {
      frappe.call({
        method: "face_attendance.api.v1.approve_registration",
        args: Object.assign({ name: frm.doc.name }, values),
        freeze: true,
        freeze_message: __("Creating employee and template…"),
      }).then(({ message }) => {
        dialog.hide();
        frappe.show_alert({
          message: __("Employee {0} created.", [message.employee]),
          indicator: "green",
        });
        frm.reload_doc();
      });
    },
  });
  dialog.show();
}

function reject_dialog(frm) {
  frappe.prompt(
    [
      {
        fieldtype: "Small Text",
        fieldname: "reason",
        label: __("Reason"),
      },
    ],
    (values) => {
      frappe.call({
        method: "face_attendance.api.v1.reject_registration",
        args: { name: frm.doc.name, reason: values.reason },
        freeze: true,
      }).then(() => {
        frappe.show_alert({ message: __("Registration rejected."), indicator: "orange" });
        frm.reload_doc();
      });
    },
    __("Reject registration"),
    __("Reject"),
  );
}
