app_name = "face_attendance"
app_title = "Face Attendance"
app_publisher = "Nishant Bhickta"
app_description = "Offline-first face attendance device management for Frappe HR"
app_email = "nishant.bhickta@gmail.com"
app_license = "MIT"

required_apps = ["hrms"]

doctype_js = {"Employee": "public/js/employee.js"}

portal_menu_items = [
    {"title": "Face registration", "route": "/face-registration"},
    {"title": "My attendance", "route": "/my-attendance"},
    {"title": "Pending face registrations", "route": "/face-registrations", "role": "HR Manager"},
]

after_install = "face_attendance.setup.install.after_install"
after_migrate = "face_attendance.setup.install.after_migrate"

scheduler_events = {
    "cron": {"*/5 * * * *": ["face_attendance.integrations.webhook.process_due_deliveries"]},
    "hourly": ["face_attendance.tasks.reconcile_pending_checkins"],
    "daily": [
        "face_attendance.tasks.expire_provisioning_tokens",
        "face_attendance.tasks.purge_expired_events",
    ],
}

doc_events = {
    "Face Attendance Event": {
        "after_insert": "face_attendance.integrations.webhook.create_deliveries",
        "before_cancel": "face_attendance.immutability.reject_mutation",
        "on_trash": "face_attendance.immutability.reject_mutation",
    },
}
