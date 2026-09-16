#!/usr/bin/env python3
"""Create a FaceAttendance template locally from five or more consented images."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys

import cv2
import numpy as np


MODEL_VERSION = "intel-face-reidentification-retail-0095-onnx-v1"
REFERENCE_POINTS = np.float32([[40.3928, 59.0815], [87.3757, 59.0815], [64.1881, 105.5481]])


def parse_args():
    parser = argparse.ArgumentParser(
        description="Generate an L2-normalized biometric template without uploading face images.",
    )
    parser.add_argument("--person-id", required=True, help="Employee Biometric Person ID")
    parser.add_argument("images", nargs="+", type=Path, help="At least five clear face images")
    return parser.parse_args()


def detect_one(detector, image, path):
    height, width = image.shape[:2]
    detector.setInputSize((width, height))
    _, faces = detector.detect(image)
    if faces is None or len(faces) != 1:
        raise ValueError(f"{path}: expected exactly one face")
    face = faces[0]
    x, y, box_width, box_height = face[:4]
    if min(box_width, box_height) < 140:
        raise ValueError(f"{path}: face must be at least 140 pixels wide and high")
    left = max(0, int(x))
    top = max(0, int(y))
    right = min(width, int(x + box_width))
    bottom = min(height, int(y + box_height))
    gray = cv2.cvtColor(image[top:bottom, left:right], cv2.COLOR_BGR2GRAY)
    brightness = float(gray.mean())
    sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    if not 45 <= brightness <= 220:
        raise ValueError(f"{path}: lighting is outside the accepted range")
    if sharpness < 70:
        raise ValueError(f"{path}: face is blurred")
    return face


def align(image, face):
    landmarks = face[4:14].reshape(5, 2)
    eyes = landmarks[:2][np.argsort(landmarks[:2, 0])]
    mouth_center = landmarks[3:5].mean(axis=0, keepdims=True)
    source = np.concatenate((eyes, mouth_center)).astype(np.float32)
    transform = cv2.getAffineTransform(source, REFERENCE_POINTS)
    return cv2.warpAffine(image, transform, (128, 128), flags=cv2.INTER_LINEAR)


def embed(network, aligned):
    network.setInput(cv2.dnn.blobFromImage(aligned, 1.0, (128, 128), swapRB=False, crop=False))
    vector = network.forward().reshape(-1).astype("<f4")
    if vector.size != 256 or not np.isfinite(vector).all():
        raise RuntimeError("recognition model returned an invalid embedding")
    return vector / np.linalg.norm(vector)


def main():
    args = parse_args()
    if len(args.images) < 5:
        raise ValueError("at least five images are required")
    root = Path(__file__).resolve().parents[1]
    detector = cv2.FaceDetectorYN.create(
        str(root / "tools/models/face_detection_yunet_2023mar.onnx"),
        "",
        (320, 320),
        score_threshold=0.9,
        nms_threshold=0.3,
        top_k=5000,
    )
    recognizer = cv2.dnn.readNetFromONNX(
        str(root / "app/src/main/assets/models/face-reidentification-retail-0095.onnx"),
    )
    vectors = []
    for path in args.images:
        image = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError(f"{path}: unreadable image")
        vectors.append(embed(recognizer, align(image, detect_one(detector, image, path))))

    similarity = np.asarray(vectors) @ np.asarray(vectors).T
    np.fill_diagonal(similarity, 1.0)
    if float(similarity.min()) < 0.65:
        raise ValueError("samples are inconsistent; retake enrollment images for one person")
    template = np.mean(vectors, axis=0)
    template = (template / np.linalg.norm(template)).astype("<f4")
    raw = template.tobytes()
    print(json.dumps({
        "person_id": args.person_id,
        "model_version": MODEL_VERSION,
        "template_version": hashlib.sha256(raw).hexdigest()[:16],
        "embedding": base64.b64encode(raw).decode("ascii"),
        "samples": len(vectors),
        "minimum_sample_similarity": round(float(similarity.min()), 6),
    }, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as error:
        print(f"enrollment failed: {error}", file=sys.stderr)
        raise SystemExit(2) from error
