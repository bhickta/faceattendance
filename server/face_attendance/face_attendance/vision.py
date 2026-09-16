"""Server-side biometric template generation from uploaded photographs.

The recognition model, reference alignment and quality gates match the offline
enrollment tool and the Android engine, so templates created here stay
compatible with device probes.
"""

import base64
import hashlib
import io
import math
from functools import lru_cache
from pathlib import Path

import cv2
import frappe
import numpy as np
from frappe import _
from PIL import Image, ImageOps

MODEL_VERSION = "intel-face-reidentification-retail-0095-onnx-v1"
EMBEDDING_DIMENSIONS = 256
MINIMUM_SAMPLE_SIMILARITY = 0.65
MINIMUM_FACE_PIXELS = 140
MINIMUM_BRIGHTNESS = 45.0
MAXIMUM_BRIGHTNESS = 220.0
MINIMUM_LAPLACIAN_VARIANCE = 70.0
RECOGNITION_SIZE = 128
REFERENCE_POINTS = np.float32(
    [[40.3928, 59.0815], [87.3757, 59.0815], [64.1881, 105.5481]]
)

_MODELS = Path(__file__).resolve().parent / "models"
_DETECTOR_MODEL = str(_MODELS / "face_detection_yunet_2023mar.onnx")
_RECOGNITION_MODEL = str(_MODELS / "face-reidentification-retail-0095.onnx")


def build_template(images):
    """Return the aggregated template for one employee from photo bytes."""
    if not images:
        frappe.throw(_("Upload at least one photograph"))

    vectors = [_embed(_decode(data, index), index) for index, data in enumerate(images)]
    similarity = np.asarray(vectors) @ np.asarray(vectors).T
    minimum_similarity = None
    if len(vectors) > 1:
        minimum_similarity = float(similarity.min())
        if minimum_similarity < MINIMUM_SAMPLE_SIMILARITY:
            frappe.throw(
                _(
                    "The photographs do not look like the same person "
                    "(minimum similarity {0}). Add clearer, consistent photos."
                ).format(round(minimum_similarity, 3))
            )

    template = np.mean(vectors, axis=0)
    template = (template / np.linalg.norm(template)).astype("<f4")
    raw = template.tobytes()
    return {
        "embedding": base64.b64encode(raw).decode("ascii"),
        "template_version": hashlib.sha256(raw).hexdigest()[:16],
        "model_version": MODEL_VERSION,
        "samples": len(vectors),
        "minimum_sample_similarity": minimum_similarity,
    }


def decode_embedding(embedding):
    raw = base64.b64decode(str(embedding), validate=True)
    if len(raw) != EMBEDDING_DIMENSIONS * 4:
        raise ValueError("unexpected embedding length")
    values = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    return values


def cosine_similarity(left, right):
    return float(np.dot(left, right))


def _decode(image_bytes, index):
    try:
        image = Image.open(io.BytesIO(image_bytes))
        image = ImageOps.exif_transpose(image).convert("RGB")
    except Exception:
        frappe.throw(_("Photo {0} is not a readable image").format(index + 1))
    array = np.asarray(image)[:, :, ::-1]
    return np.ascontiguousarray(array)


def _embed(image, index):
    height, width = image.shape[:2]
    detector = _detector()
    detector.setInputSize((width, height))
    _, faces = detector.detect(image)
    if faces is None or len(faces) != 1:
        frappe.throw(
            _("Photo {0} must contain exactly one face").format(index + 1)
        )

    face = faces[0]
    x, y, box_width, box_height = face[:4]
    if min(box_width, box_height) < MINIMUM_FACE_PIXELS:
        frappe.throw(
            _("Photo {0}: use a larger, closer face (at least {1} pixels)").format(
                index + 1, MINIMUM_FACE_PIXELS
            )
        )

    left = max(0, int(x))
    top = max(0, int(y))
    right = min(width, int(x + box_width))
    bottom = min(height, int(y + box_height))
    gray = cv2.cvtColor(image[top:bottom, left:right], cv2.COLOR_BGR2GRAY)
    brightness = float(gray.mean())
    sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    if not MINIMUM_BRIGHTNESS <= brightness <= MAXIMUM_BRIGHTNESS:
        frappe.throw(_("Photo {0}: lighting is outside the accepted range").format(index + 1))
    if sharpness < MINIMUM_LAPLACIAN_VARIANCE:
        frappe.throw(_("Photo {0} is blurred; hold still and retake").format(index + 1))

    aligned = _align(image, face)
    network = _recognizer()
    network.setInput(
        cv2.dnn.blobFromImage(
            aligned, 1.0, (RECOGNITION_SIZE, RECOGNITION_SIZE), swapRB=False, crop=False
        )
    )
    vector = network.forward().reshape(-1).astype("<f4").astype(np.float64)
    if vector.size != EMBEDDING_DIMENSIONS or not math.isfinite(float(vector.sum())):
        frappe.throw(_("Photo {0} produced an invalid face template").format(index + 1))
    return vector / float(np.linalg.norm(vector))


def _align(image, face):
    landmarks = face[4:14].reshape(5, 2)
    eyes = landmarks[:2][np.argsort(landmarks[:2, 0])]
    mouth = landmarks[3:5].mean(axis=0, keepdims=True)
    source = np.concatenate((eyes, mouth)).astype(np.float32)
    transform = cv2.getAffineTransform(source, REFERENCE_POINTS)
    return cv2.warpAffine(
        image, transform, (RECOGNITION_SIZE, RECOGNITION_SIZE), flags=cv2.INTER_LINEAR
    )


@lru_cache(maxsize=1)
def _detector():
    _require_opencv()
    return cv2.FaceDetectorYN.create(_DETECTOR_MODEL, "", (320, 320), 0.9, 0.3, 5000)


@lru_cache(maxsize=1)
def _recognizer():
    _require_opencv()
    return cv2.dnn.readNetFromONNX(_RECOGNITION_MODEL)


def _require_opencv():
    if not cv2.__version__:
        frappe.throw(_("OpenCV is not available on the server"))
