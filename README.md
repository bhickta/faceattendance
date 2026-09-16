# FaceAttendance

Android face-attendance kiosk starter for company-owned, fully managed devices.

**Owner:** Nishant Bhickta (`bhickta`)
**Contact:** nishant.bhickta@gmail.com

## Current scope

This repository contains the Android kiosk and its separately packaged Frappe server app:

- Native Kotlin Android application (Java provides no material runtime benefit here)
- CameraX front-camera preview
- On-device ML Kit face detection
- On-device Intel face embeddings with face alignment and image-quality gates
- Two-model MiniFASNet passive liveness plus randomized head-turn challenge
- Keystore-encrypted, branch-aware offline biometric roster
- Device Owner receiver and dedicated-device restrictions
- Lock Task kiosk mode
- Launch after boot
- HTTPS-only network policy
- Room-backed immutable punch ledger
- Hardware-backed device signing and encrypted API credentials
- One-time kiosk activation and seven-day authorization lease
- Signed, idempotent batch synchronization
- Frappe HR Employee Checkin projection and signed webhook delivery
- English and Hindi kiosk resources
- CI build workflow

Both debug and release builds use the real offline biometric pipeline. Punches remain disabled until
the kiosk has synchronized at least one compatible biometric template. Model files, artifact hashes,
licenses, conversion details and the limits of their published metrics are recorded in
[`docs/MODEL_CARD.md`](docs/MODEL_CARD.md). Supervisor fallback, supported-hardware qualification and
representative field validation are still required before a production rollout.

## Components

- `app/`: Android kiosk application
- `server/face_attendance/`: installable custom Frappe application requiring Frappe HR
- `docs/`: architecture and implementation roadmap

## Development setup

For first installation, ERPNext activation, on-phone employee enrollment and attendance use, follow
the [quick-start guide](docs/QUICKSTART.md).

Requirements:

- Android Studio with Android SDK 35
- JDK 17 or newer
- An Android 8.0+ test device with a front camera

Build the debug APK:

```bash
./gradlew assembleDebug
```

Every successful GitHub Actions run publishes the debug APK as a workflow artifact for 14 days.
Pushing a `v*` tag builds a signed APK and publishes it in that tag's GitHub Release assets.
Configure these repository secrets before tagging a release:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Keep the release keystore backed up outside GitHub. Losing it prevents compatible upgrades to
installed production devices.

Install it normally for UI and camera development:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Device Owner development setup

Device Owner enrollment controls the whole tablet. Use a disposable or freshly factory-reset test device with no accounts, secondary users or work profile.

```bash
adb shell dpm set-device-owner \
  com.bhickta.faceattendance/.device.AttendanceDeviceAdminReceiver
```

Then launch the app. It allowlists itself for Lock Task mode and applies the starter dedicated-device restrictions.

Production fleets should use Android Enterprise QR or zero-touch provisioning. Do not rely on ADB enrollment outside development.

## Frappe server

Install the custom server app on a Frappe private bench with Frappe HR. Installation and device
setup notes are in [`server/face_attendance/README.md`](server/face_attendance/README.md).

## Safety and privacy

- Use HTTPS for all API traffic.
- Store face embeddings encrypted with keys protected by Android Keystore.
- Avoid retaining or uploading raw face images unless strictly necessary.
- Validate recognition thresholds against the real deployment population.
- Add presentation-attack detection; blink-only checks are insufficient.
- Treat the checked-in thresholds as pilot defaults; approve calibrated thresholds per hardware model.
- Always provide a documented fallback and attendance dispute process.

## License

MIT © 2026 Nishant Bhickta. See [LICENSE](LICENSE).
