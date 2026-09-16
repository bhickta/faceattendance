# Face Attendance Frappe app

Install this app on a private bench with Frappe HR:

```bash
bench get-app /path/to/face_attendance
bench --site customer.example.com install-app hrms
bench --site customer.example.com install-app face_attendance
```

Create a dedicated Frappe user and an `Attendance Device` record for every kiosk. Set its
branch, gate and direction. Device activation tooling will bind the Android signing key and
deliver credentials; until that workflow is enabled, provisioning is an administrator-only
operation.

The ingestion endpoint is:

```text
POST /api/method/face_attendance.api.v1.submit_events
```

It requires Frappe token authentication plus an ECDSA signature in `X-Device-Signature`.
