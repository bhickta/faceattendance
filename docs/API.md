# Face Attendance API

All endpoints live under `/api/method/face_attendance.api.<module>.<method>` on your Frappe site
and speak JSON. Authentication is either a Frappe **API key/secret token** or a signed-in session.

## Device API (`face_attendance.api.v1`)

Devices authenticate with the restricted kiosk user's API key/secret and sign the raw request body
with the P-256 key bound at activation (header `X-Device-Signature`). Every body carries
`schema_version: 1`.

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `activate_device` | POST (guest) | Exchange a one-time activation token + public key for API credentials. |
| `sync_state` | POST | Lease, assignment (`branch`, `gate`, `direction mode`) and server time. |
| `sync_roster` | POST | Branch roster of normalized templates, versioned by SHA-256. |
| `submit_events` | POST | Idempotent batch of attendance events (max 100). |
| `submit_self_registration` | POST | Unknown person self-registration for HR approval. |

Signing: `signature = base64(ECDSA_SHA256(private_key, raw_body))`. Event ingestion is idempotent on
`event_id` and rejects reused device sequences.

## Integration API (`face_attendance.api.insights`)

Read-only, role-checked (`System Manager` or `HR Manager`), paginated (`page`, `page_size` up to 1000).

| Endpoint | Returns |
| --- | --- |
| `health` | Status, device freshness, template/employee counts, pending registrations and deliveries. |
| `device_status` | Every device with `age_minutes`, `activated` and `healthy`. |
| `roster_status` | Enrolled vs missing employees, plus the branch list. |
| `attendance_events` | Raw events for a date range for external sync. |
| `attendance_summary` | Per employee/day first in, last out, punches, gross hours. |
| `my_attendance` | The signed-in employee's own summary (employee portal). |

Parameters for `attendance_summary` / `attendance_events`:
`from_date`, `to_date` (`YYYY-MM-DD`, default last 30 days), `branch`, `employee` (summary/events),
`disposition` (events), `page`, `page_size`.

Example:

```bash
curl -H "Authorization: token <api_key>:<api_secret>" \
  "https://your-site/api/method/face_attendance.api.insights.attendance_summary?from_date=2026-09-01&to_date=2026-09-30&page_size=500"
```

## Outbound webhooks

Accepted events are queued per enabled `Integration Destination` and delivered with:

- `X-Face-Attendance-Timestamp`, `X-Face-Attendance-Signature: sha256=<hmac>`
  where `hmac = HMAC_SHA256(secret, timestamp + "." + raw_body)`
- `Idempotency-Key: <event_id>`

Retries use exponential backoff up to `max_attempts`; `429` and `5xx` are retried, other `4xx` fail
permanently. Destination URLs must be public HTTPS on port 443.

Verify on your side:

```python
expected = hmac.new(secret, timestamp.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
assert hmac.compare_digest(expected, provided_signature)
```

## Roles

| Role | Access |
| --- | --- |
| System Manager | Everything, including deleting attendance evidence and approving registrations. |
| HR Manager | Enrolment, approvals, reports, integration API. |
| Employee | Own attendance via `/my-attendance` and `/face-registration`. |
| Kiosk device user | Only the signed device endpoints. |
