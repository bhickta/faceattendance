# Install and start using FaceAttendance

## 1. Server setup

Use a private Frappe/ERPNext bench with Frappe HR installed:

```bash
git clone --branch v0.2.0 --depth 1 https://github.com/bhickta/faceattendance.git /tmp/faceattendance
bench get-app /tmp/faceattendance/server/face_attendance
bench --site your-site.example install-app hrms
bench --site your-site.example install-app face_attendance
bench --site your-site.example migrate
bench build --app face_attendance
```

The site must be reachable through HTTPS from every kiosk.

In ERPNext:

1. Create a dedicated enabled User for the kiosk. Do not give it Desk or HR roles.
2. Create an **Attendance Device** with that user, branch, gate and IN/OUT mode.
3. Save it and click **Issue Activation Token**.

## 2. Android installation

Download `FaceAttendance-v0.2.0-arm64.apk` from the GitHub Release on an ARM64 Android 8.0+
phone/tablet. Allow installation from the browser or file manager, install it, then open it.

Enter the HTTPS ERPNext URL and the one-time activation token. The first installation can be tested
as a normal app. Device Owner provisioning is required before deploying a locked production kiosk.

## 3. First employee enrollment

### Option A — register from a photograph in the portal

1. Open an Employee in ERPNext and set a unique **Biometric Person ID**, such as `EMP-1042`.
2. Save the Employee and click **Face Attendance → Register Face from Photo**.
3. Upload one to ten front-facing photographs, confirm employee consent and register.
4. Leave **Branch** blank to distribute the employee to every device, or set a branch.
5. Every enabled kiosk picks up the employee on its next roster sync.

The server embeds the face with the same model and alignment as the kiosk. Photographs are
processed in memory and are never stored.

### Option B — enrol on the kiosk with a one-time token

1. Open an Employee in ERPNext and set a unique **Biometric Person ID**, such as `EMP-1042`.
2. Save the Employee and click **Face Attendance → Issue Face Enrollment Token**.
3. On the Android device, tap **Enroll employee with one-time token**.
4. Enter the token and capture five samples, changing position slightly between samples.
5. When **Enrollment complete — roster is ready** appears, return to the attendance screen.

The device sends only the normalized 256-float template. Raw enrollment photographs are not saved or
uploaded. Tokens expire after 15 minutes and can be used once.

## 4. Attendance

The employee positions one face in view, selects **Check in** or **Check out**, looks straight, follows
the randomized head-turn instruction, and returns to center. Accepted punches are written to the
local immutable ledger immediately and synchronized to ERPNext when connectivity is available.

Repeat enrollment for additional employees. Templates with a branch are distributed only to kiosks
assigned to that branch.

## Production gate

Before payroll use, fix the supported tablet/camera model and validate recognition and spoof
thresholds with representative employees and lighting. Configure Android Enterprise Device Owner,
release signing backups, a non-biometric fallback, retention/deletion procedures and attendance
dispute handling. The included defaults are pilot defaults, not a biometric certification.
