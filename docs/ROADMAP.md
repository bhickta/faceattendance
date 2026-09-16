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

- [x] Select a commercially usable embedding model and document its provenance
- [x] Face alignment and image-quality gates
- [x] Consent-bound, one-time-token multi-frame enrollment
- [x] Encrypted embedding storage backed by Android Keystore
- [ ] Calibrate accept/reject thresholds using deployment data
- [x] Duplicate-person detection during enrollment

## Milestone 3 — liveness

- [x] Passive presentation-attack model
- [x] Randomized active challenge
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
- [x] Branch-aware, versioned roster synchronization
- [ ] Frappe Last Sync of Checkin coordination

## Milestone 5 — production hardening

- [ ] Threat model and independent security review
- [ ] Crash recovery and health heartbeat
- [ ] Signed remote configuration and application update process
- [ ] Privacy notice, retention policy and deletion workflow
- [ ] Recognition and liveness evaluation across real devices and users
- [ ] Accessibility and non-biometric fallback

## Release blocker

The open-weight offline engine and secure roster distribution are implemented. Production release
remains blocked until multi-frame enrollment and revocation operations are completed, supervisor
exceptions are implemented, privacy/legal review is recorded, supported devices are fixed, and
representative field testing meets documented recognition and presentation-attack targets.
