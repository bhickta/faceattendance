# Implementation roadmap

## Milestone 1 — kiosk foundation

- [x] Native Android project
- [x] CameraX preview and ML Kit face detection
- [x] Device Owner receiver and Lock Task policy
- [x] Boot launch and HTTPS-only configuration
- [ ] Admin PIN with rate limiting and secure recovery
- [ ] QR provisioning payload and managed configuration

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

- [ ] Check-in/out policy and duplicate guard
- [ ] Room-backed immutable event outbox
- [ ] WorkManager upload and exponential backoff
- [ ] Device authentication and idempotent HTTPS API
- [ ] Server acknowledgements and audit history
- [ ] Clock-tampering detection

## Milestone 5 — production hardening

- [ ] Threat model and independent security review
- [ ] Crash recovery and health heartbeat
- [ ] Signed remote configuration and application update process
- [ ] Privacy notice, retention policy and deletion workflow
- [ ] Recognition and liveness evaluation across real devices and users
- [ ] Accessibility and non-biometric fallback
