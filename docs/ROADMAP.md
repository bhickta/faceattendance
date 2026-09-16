# Implementation roadmap

## Milestone 1 — kiosk foundation

- [x] Native Android project
- [x] CameraX preview and ML Kit face detection
- [x] Device Owner receiver and Lock Task policy
- [x] Boot launch and HTTPS-only configuration
- [ ] Admin PIN with rate limiting and secure recovery
- [x] One-time server activation and hardware-backed device credentials
- [ ] Android Enterprise QR provisioning payload and managed configuration

## Milestone 2 — recognition

- [ ] Select a commercially usable embedding model and document its provenance
- [ ] Face alignment and image-quality gates
- [ ] Multi-frame enrollment
- [ ] Encrypted embedding storage backed by Android Keystore
- [ ] Calibrate accept/reject thresholds using deployment data
- [ ] Duplicate-person detection during enrollment

## Milestone 3 — liveness

- [ ] Passive presentation-attack model
- [ ] Randomized active challenge
- [ ] Replay, printed-photo and screen-photo evaluation
- [ ] Manual fallback and review workflow

## Milestone 4 — attendance and synchronization

- [x] Explicit IN/OUT policy and local duplicate guard
- [x] Room-backed immutable event outbox
- [x] WorkManager upload and exponential backoff
- [x] Device authentication, signatures and idempotent HTTPS ingestion
- [x] Per-event acknowledgements and immutable server evidence
- [x] Clock-tampering evidence and seven-day authorization lease
- [x] Frappe HR Employee Checkin projection
- [x] Signed generic HTTPS webhook outbox
- [ ] Roster completeness and Frappe Last Sync of Checkin coordination

## Milestone 5 — production hardening

- [ ] Threat model and independent security review
- [ ] Crash recovery and health heartbeat
- [ ] Signed remote configuration and application update process
- [ ] Privacy notice, retention policy and deletion workflow
- [ ] Recognition and liveness evaluation across real devices and users
- [ ] Accessibility and non-biometric fallback

## Release blocker

The current code is an engineering foundation, not a production biometric product. Production release
is blocked until a licensed SDK is integrated and certified on selected devices, enrollments and
templates are distributed securely, supervisor exceptions are implemented, and representative field
testing meets the documented accuracy and liveness thresholds.
