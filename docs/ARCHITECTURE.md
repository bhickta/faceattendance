# Architecture

## Target flow

```text
CameraX frame
  -> face detection and quality gate
  -> liveness / presentation-attack detection
  -> aligned face crop
  -> on-device embedding model
  -> encrypted local template match
  -> attendance policy and duplicate guard
  -> durable local outbox
  -> authenticated HTTPS synchronization
```

## Suggested modules

- `device`: Device Owner, Lock Task, boot and administrative recovery
- `vision`: capture, detection, alignment, liveness and embeddings
- `enrollment`: consent, multi-frame enrollment and duplicate detection
- `attendance`: check-in/out policy and review states
- `storage`: encrypted templates and Room-backed outbox
- `sync`: WorkManager upload with idempotency and exponential backoff
- `admin`: protected provisioning, diagnostics and kiosk exit

## Synchronization contract

Each event should have a UUID generated before the network request. The server must enforce uniqueness on that ID so retries cannot duplicate attendance.

```json
{
  "event_id": "c3707a83-1901-4a67-bf89-cc335dd79f9b",
  "person_id": "EMP-1042",
  "device_id": "KIOSK-03",
  "event_type": "CHECK_IN",
  "captured_at": "2026-09-16T08:57:21+05:30",
  "match_score": 0.81,
  "liveness_passed": true,
  "model_version": "face-v1"
}
```

The backend should authenticate each managed device, record server receipt time, validate timestamps and return an explicit acknowledgement for every accepted event.
