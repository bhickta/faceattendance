# Administrator runbook

## Install

```bash
bench get-app /path/to/server/face_attendance
bench --site your-site install-app hrms
bench --site your-site install-app face_attendance
bench --site your-site migrate
bench build --app face_attendance
```

The app requires Frappe 15, ERPNext and HRMS. Server-side photo enrolment additionally needs
`opencv-python-headless` (declared in `pyproject.toml`) and the `tzdata` package.

## Roles

- **System Manager** — configuration, deleting evidence, approving registrations.
- **HR Manager** — enrol employees, approve/reject registrations, run reports.
- Kiosk **device users** are ordinary users with no Desk/HR roles and only API credentials.

## Provisioning a kiosk

1. Create a dedicated enabled User for the kiosk (no Desk or HR roles).
2. Create an **Attendance Device** with that user, branch, gate and direction mode.
3. Save it and click **Issue Activation Token**, or POST
   `face_attendance.api.v1.create_provisioning_token` with `{"device_id": "..."}`.
4. Enter the site URL and token on the phone. Activation binds the device key and rotates its
   credentials automatically.

## Enrolling employees

- **Portal/Desk photo** — Employee → **Register Face from Photo**, or the profile picture option.
  One to ten photos; blank **Allowed Branches** distributes the employee to every device.
- **Employee self-service** — enable *Allow Employees to Self-Register from the Portal* in
  **Face Attendance Settings**; employees use `/face-registration`.
- **Kiosk self-registration** — an unknown person can register at a kiosk; HR approves from the
  **Face Attendance Registration** form or `/face-registrations`. A temporary employee is created and
  converted to a full employee on approval.

The server never stores raw photographs for enrolment; only the 256-float template is kept.

## Attendance model

Accepted events write an immutable **Face Attendance Event** and, in *Managed Attendance* mode, an
**Employee Checkin**. Quarantine reasons include `untrusted_timestamp`, `future_timestamp`,
`outside_offline_window` (older than 8 days) and `stale_assignment`. Devices work fully offline and
sync when connectivity returns; the authorization lease is 7 days, after which the kiosk must come
online once to renew.

## Reports

- **Daily Attendance Summary** — first in, last out, punches and gross hours per employee/day.
- **Missing Checkout** — employees with a check in but no check out that day.
- **Device Health** — freshness, activation and health per device.

## Retention

`Face Attendance Settings.event_retention_days` (default 90) drives a daily purge
(`face_attendance.tasks.purge_expired_events`). The purge keeps Employee Checkins and removes only
the raw event evidence; set `0` to disable.

## Integrations

- **Outbound**: configure an **Integration Destination** (public HTTPS URL + signing secret) to
  stream every accepted event, with HMAC signatures, idempotency keys and exponential retries.
- **Inbound**: use the read-only Integration API (`face_attendance.api.insights.*`) with a Frappe API
  key/secret to pull events or daily summaries into any HR/payroll system.

See `docs/API.md` for payloads and verification snippets.

## Monitoring

- `face_attendance.api.insights.health` — status snapshot for dashboards/alerting.
- The kiosk header shows online/offline state, pending punches to sync and roster readiness.

## Scaling notes

- Events are unique on `(device, device_sequence)`; ingestion is idempotent and batched.
- Webhook delivery and check-in reconciliation run as background jobs on the `short` queue.
- The employee roster sync is versioned (SHA-256) so devices only download changes.
