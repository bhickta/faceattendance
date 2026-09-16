# Face Attendance Frappe app

Install this app on a private bench with Frappe HR:

```bash
bench get-app /path/to/face_attendance
bench --site customer.example.com install-app hrms
bench --site customer.example.com install-app face_attendance
```

Create a dedicated Frappe user and an `Attendance Device` record for every kiosk. Set its
branch, gate and direction. Call `face_attendance.api.v1.create_provisioning_token` as a
System Manager and enter the returned one-time token on the kiosk. Activation binds the
Android signing key and rotates the restricted device user's API credentials.

## Register an employee from a photograph

A System Manager or HR Manager can enrol a face from the portal without using a kiosk:

1. Open an Employee and set a unique `Biometric Person ID`.
2. Click **Face Attendance → Register Face from Photo**.
3. Upload one to ten front-facing photographs, confirm consent, choose a branch (leave it
   blank to distribute the employee to every device) and register.

The server detects, aligns and embeds the face with the same model and alignment as the
kiosk, then stores only the 256-float template. Photographs are processed in memory and are
not stored as biometric data. Every enabled device receives the employee on its next roster
sync.

## Biometric roster

Set a unique `Biometric Person ID` on each participating Employee, then create one enabled
`Biometric Template` per employee for model version
`intel-face-reidentification-retail-0095-onnx-v1`. The embedding field is exactly 256 normalized
float32 values serialized little-endian and base64 encoded. A blank template branch distributes it
to every kiosk; otherwise it is sent only to devices assigned to that branch.

Kiosks request a signed, versioned roster during activation and every periodic sync. The server sends
the full branch roster only when its SHA-256 version changes. Android validates its dimensions,
normalization and model version, then atomically encrypts it with AES-GCM under an Android Keystore
key. No raw face image is part of this protocol.

Do not copy embeddings from another recognition model: embedding spaces are incompatible. Enrollment
must use the exact checked-in model and alignment procedure, multiple high-quality samples, employee
consent and duplicate-person review. The kiosk correctly stays unavailable when the roster is empty
or incompatible.

Generate a template on a controlled enrollment workstation:

```bash
python3 -m venv .enrollment-venv
.enrollment-venv/bin/pip install -r tools/requirements-enrollment.txt
.enrollment-venv/bin/python tools/enroll_face.py \
  --person-id EMP-1042 employee-front-1.jpg employee-front-2.jpg \
  employee-front-3.jpg employee-front-4.jpg employee-front-5.jpg
```

Copy `model_version`, `template_version`, and `embedding` from the JSON output into the employee's
Biometric Template. Delete enrollment photographs according to the approved retention policy.

The ingestion endpoint is:

```text
POST /api/method/face_attendance.api.v1.submit_events
```

It requires Frappe token authentication plus an ECDSA signature in `X-Device-Signature`.
