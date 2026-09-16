# FaceAttendance

Android face-attendance kiosk starter for company-owned, fully managed devices.

**Owner:** Nishant Bhickta (`bhickta`)
**Contact:** nishant.bhickta@gmail.com

## Current scope

This repository establishes the device and camera foundation:

- Native Kotlin Android application
- CameraX front-camera preview
- On-device ML Kit face detection
- Device Owner receiver and dedicated-device restrictions
- Lock Task kiosk mode
- Launch after boot
- HTTPS-only network policy
- CI build workflow

Face detection is intentionally not presented as face recognition. A recognition model, enrollment workflow, liveness validation, encrypted template storage and attendance synchronization still need to be implemented before production use. See [the implementation roadmap](docs/ROADMAP.md).

## Development setup

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

## Safety and privacy

- Use HTTPS for all API traffic.
- Store face embeddings encrypted with keys protected by Android Keystore.
- Avoid retaining or uploading raw face images unless strictly necessary.
- Validate recognition thresholds against the real deployment population.
- Add presentation-attack detection; blink-only checks are insufficient.
- Always provide a documented fallback and attendance dispute process.

## License

MIT © 2026 Nishant Bhickta. See [LICENSE](LICENSE).
