# Offline biometric model card

## Recognition

- Artifact: `face-reidentification-retail-0095.onnx`
- Upstream: Intel Open Model Zoo `face-reidentification-retail-0095`, 2023.0 FP16 IR
- Upstream license: Apache-2.0 (included in the APK)
- Upstream FP16 XML SHA-384: `689fb39e94bbd0d22cfeeb9292d0b91ac753424a7ee2c0555070e4038a00bfcaad473b47303012b9a393027dbad0dbce`
- Upstream FP16 BIN SHA-384: `6d51703e854509dafdab9dfc6b932136d301d95be6d52c9c23b5a4f5de1ffec80e8d38b87e0e9556ad36f0dded1588ed`
- Distributed ONNX SHA-256: `d6dd49c059140766c3176fd7957ce45f76b5098fa7f4f48e523bb20212c61c11`

The IR was converted with `openvino2onnx` and its output was compared with OpenVINO Runtime on a
seeded random tensor. Maximum absolute output difference was `1.97e-6`; cosine similarity was
`1.0000001`. The network produces a 256-float embedding from an aligned 128x128 BGR face.

Intel reports 0.9947 LFW pair-verification accuracy. That number is not an attendance-system false
accept rate and must not be used as a production acceptance claim.

## Passive presentation-attack detection

- Upstream: Minivision `Silent-Face-Anti-Spoofing`, commit
  `b6d5f04ad78778917853b25c778acef6d5626d15`
- Upstream license: Apache-2.0 (included in the APK)
- `2.7_80x80_MiniFASNetV2.pth` SHA-256: `a5eb02e1843f19b5386b953cc4c9f011c3f985d0ee2bb9819eea9a142099bec0`
- Distributed ONNX SHA-256: `b914e5ad48bacc4c22c777aecb73847ba7f1e10b6a1a6723ce8e61f86270a2a2`
- `4_0_0_80x80_MiniFASNetV1SE.pth` SHA-256: `84ee1d37d96894d5e82de5a57df044ef80a58be2b218b5ed7cdfd875ec2f5990`
- Distributed ONNX SHA-256: `fe7ba89a0cf6f54048f3e500116efd0f1da20f0dda5d11779ef32870d2b7d6c1`

The two 80x80 models are exported by `tools/export_minifasnet_onnx.py`; their live-class
probabilities are averaged. Passive RGB presentation-attack detection is camera and environment
dependent. It must be combined with the randomized active challenge and validated against printed
photos, screens, replay video and masks on every supported kiosk model.

## Decision thresholds

The checked-in thresholds are conservative pilot defaults, not certified universal values:

- cosine similarity: `0.72`
- best-versus-second-best margin: `0.08`
- passive liveness probability: `0.85`
- yaw: `20 degrees`; roll: `15 degrees`
- minimum face size: `140px`; minimum frame coverage: `8%`

Release approval requires a representative, consented evaluation set and documented FAR, FRR,
failure-to-acquire, demographic slices, lighting/camera slices, and presentation-attack results.
Raw evaluation images must not be shipped to kiosks.

## Legal boundary

The repository records the publishers' artifact licenses and checksums; this is not a legal opinion.
Before selling in a jurisdiction, counsel must review biometric/privacy obligations and the training
data provenance. Employee consent or another valid legal basis, retention/deletion rules, access
controls, an appeal path, and a non-biometric fallback remain mandatory product requirements.

## Enrollment utility

`tools/enroll_face.py` performs local multi-image enrollment with the same recognition model and
three-point alignment used on Android. It requires at least five images, rejects multiple/small,
dark, overexposed, blurry or mutually inconsistent samples, and prints a normalized base64 template
for the Frappe `Biometric Template` record. Images are read locally and are never transmitted.

Enrollment detection uses OpenCV Zoo YuNet `face_detection_yunet_2023mar.onnx`, whose directory is
MIT licensed. Distributed SHA-256: `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`.
