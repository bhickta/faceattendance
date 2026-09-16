package com.bhickta.faceattendance.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.bhickta.faceattendance.attendance.RecognitionEvidence
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Fully offline recognition and passive presentation-attack pipeline. */
class OfflineBiometricEngine(context: Context) : BiometricEngine {
    private val appContext = context.applicationContext
    private val rosterStore = BiometricRosterStore(appContext)
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build(),
    )
    private val initialization = runCatching {
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime failed to initialize" }
        Models(
            recognition = loadModel(RECOGNITION_ASSET),
            passiveLiveness = LIVENESS_ASSETS.map(::loadModel),
        )
    }

    override val isReady: Boolean
        get() = initialization.isSuccess && rosterStore.get()?.templates?.isNotEmpty() == true

    override val isEnrollmentReady: Boolean
        get() = initialization.isSuccess

    override val rosterSize: Int
        get() = rosterStore.get()?.templates?.size ?: 0

    override suspend fun identify(bitmap: Bitmap): BiometricResult {
        val roster = rosterStore.get()
            ?: return BiometricResult.Unavailable("Biometric roster has not synchronized")
        if (roster.templates.isEmpty()) {
            return BiometricResult.Unavailable("Biometric roster is empty")
        }
        return when (val capture = capture(bitmap)) {
            is CaptureResult.QualityRejected -> BiometricResult.NoMatch
            CaptureResult.LivenessFailed -> BiometricResult.LivenessFailed
            is CaptureResult.Unavailable -> BiometricResult.Unavailable(capture.reason)
            is CaptureResult.Success -> {
                val match = CosineMatcher.best(
                    probe = capture.embedding,
                    templates = roster.templates,
                    threshold = MATCH_THRESHOLD,
                    minimumMargin = MINIMUM_MATCH_MARGIN,
                ) ?: return BiometricResult.NoMatch
                BiometricResult.Match(
                    evidence = RecognitionEvidence(
                        personId = match.template.personId,
                        matchScore = match.similarity.coerceIn(0f, 1f).toDouble(),
                        livenessScore = capture.livenessScore.toDouble(),
                        modelVersion = MODEL_VERSION,
                        templateVersion = match.template.templateVersion,
                        rosterVersion = roster.version,
                    ),
                    displayName = match.template.displayName,
                )
            }
        }
    }

    override suspend fun enroll(bitmap: Bitmap): EnrollmentResult = when (val capture = capture(bitmap)) {
        is CaptureResult.QualityRejected -> EnrollmentResult.QualityRejected(capture.reason)
        CaptureResult.LivenessFailed -> EnrollmentResult.LivenessFailed
        is CaptureResult.Unavailable -> EnrollmentResult.Unavailable(capture.reason)
        is CaptureResult.Success -> EnrollmentResult.Sample(
            embedding = capture.embedding,
            livenessScore = capture.livenessScore.toDouble(),
        )
    }

    private suspend fun capture(bitmap: Bitmap): CaptureResult {
        val models = initialization.getOrElse {
            return CaptureResult.Unavailable("Model initialization failed")
        }
        val working = downscale(bitmap)
        val frame = Mat()
        return try {
            val faces = detect(working)
            if (faces.isEmpty()) return CaptureResult.QualityRejected("no_face")
            if (faces.size > 1) return CaptureResult.QualityRejected("multiple_faces")
            val face = faces.single()
            Utils.bitmapToMat(working, frame)
            if (!passesPoseAndSize(face, frame)) return CaptureResult.QualityRejected("pose_or_size")
            val quality = imageQuality(frame, face.boundingBox)
            if (!quality.acceptable) return CaptureResult.QualityRejected("image_quality")
            val liveness = passiveLiveness(models.passiveLiveness, frame, face.boundingBox)
            if (liveness < LIVENESS_THRESHOLD) return CaptureResult.LivenessFailed
            val aligned = align(frame, face) ?: return CaptureResult.QualityRejected("landmarks")
            val vector = try {
                embedding(models.recognition, aligned)
            } finally {
                aligned.release()
            }
            CaptureResult.Success(vector, liveness)
        } catch (_: Throwable) {
            CaptureResult.Unavailable("Offline biometric processing failed")
        } finally {
            frame.release()
            if (working !== bitmap) working.recycle()
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAXIMUM_WORKING_DIMENSION) return bitmap
        val ratio = MAXIMUM_WORKING_DIMENSION.toDouble() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * ratio).toInt()),
            max(1, (bitmap.height * ratio).toInt()),
            true,
        )
    }

    private suspend fun detect(bitmap: Bitmap): List<Face> = suspendCancellableCoroutine { continuation ->
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(emptyList()) }
    }

    private fun passesPoseAndSize(face: Face, frame: Mat): Boolean {
        val box = face.boundingBox
        val coverage = box.width().toDouble() * box.height() / (frame.width() * frame.height())
        return box.width() >= MINIMUM_FACE_PIXELS &&
            box.height() >= MINIMUM_FACE_PIXELS &&
            coverage >= MINIMUM_FACE_COVERAGE &&
            abs(face.headEulerAngleY) <= MAXIMUM_YAW_DEGREES &&
            abs(face.headEulerAngleZ) <= MAXIMUM_ROLL_DEGREES
    }

    private fun imageQuality(frame: Mat, box: Rect): ImageQuality {
        val bounded = boundedRect(box, frame.width(), frame.height()) ?: return ImageQuality(false)
        val face = frame.submat(bounded)
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        return try {
            Imgproc.cvtColor(face, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, deviation)
            val brightness = Core.mean(gray).`val`[0]
            val sharpness = deviation.get(0, 0)[0] * deviation.get(0, 0)[0]
            ImageQuality(
                brightness in MINIMUM_BRIGHTNESS..MAXIMUM_BRIGHTNESS &&
                    sharpness >= MINIMUM_LAPLACIAN_VARIANCE,
            )
        } finally {
            face.release()
            gray.release()
            laplacian.release()
            mean.release()
            deviation.release()
        }
    }

    private fun passiveLiveness(models: List<Net>, frame: Mat, box: Rect): Float {
        val scales = floatArrayOf(2.7f, 4f)
        return models.indices.map { index ->
            val crop = scaledCrop(frame, box, scales[index])
            val bgr = Mat()
            val blob: Mat
            try {
                Imgproc.cvtColor(crop, bgr, Imgproc.COLOR_RGBA2BGR)
                // The pinned MiniFASNet checkpoints expect raw 0-255 BGR input:
                // upstream's ToTensor returns img.float() without dividing by 255.
                blob = Dnn.blobFromImage(
                    bgr,
                    1.0,
                    Size(80.0, 80.0),
                    Scalar(0.0),
                    false,
                    false,
                )
            } finally {
                crop.release()
            }
            try {
                models[index].setInput(blob)
                val output = models[index].forward()
                try {
                    softmax(output)[LIVE_CLASS_INDEX]
                } finally {
                    output.release()
                }
            } finally {
                blob.release()
                bgr.release()
            }
        }.average().toFloat()
    }

    private fun align(frame: Mat, face: Face): Mat? {
        val leftEye = face.landmark(FaceLandmark.LEFT_EYE) ?: return null
        val rightEye = face.landmark(FaceLandmark.RIGHT_EYE) ?: return null
        val leftMouth = face.landmark(FaceLandmark.MOUTH_LEFT) ?: return null
        val rightMouth = face.landmark(FaceLandmark.MOUTH_RIGHT) ?: return null
        val mouth = PointF(
            (leftMouth.x + rightMouth.x) / 2f,
            (leftMouth.y + rightMouth.y) / 2f,
        )
        val source = MatOfPoint2f(
            Point(leftEye.x.toDouble(), leftEye.y.toDouble()),
            Point(rightEye.x.toDouble(), rightEye.y.toDouble()),
            Point(mouth.x.toDouble(), mouth.y.toDouble()),
        )
        val destination = MatOfPoint2f(
            Point(40.3928, 59.0815),
            Point(87.3757, 59.0815),
            Point(64.1881, 105.5481),
        )
        val transform = Imgproc.getAffineTransform(source, destination)
        val aligned = Mat()
        try {
            Imgproc.warpAffine(
                frame,
                aligned,
                transform,
                Size(RECOGNITION_SIZE.toDouble(), RECOGNITION_SIZE.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE,
            )
            return aligned
        } catch (error: Throwable) {
            aligned.release()
            throw error
        } finally {
            source.release()
            destination.release()
            transform.release()
        }
    }

    private fun embedding(model: Net, alignedRgba: Mat): FloatArray {
        val bgr = Mat()
        Imgproc.cvtColor(alignedRgba, bgr, Imgproc.COLOR_RGBA2BGR)
        val blob = Dnn.blobFromImage(bgr, 1.0, Size(128.0, 128.0), Scalar(0.0), false, false)
        return try {
            model.setInput(blob)
            val output = model.forward()
            try {
                val values = FloatArray(BiometricRosterStore.EMBEDDING_SIZE)
                output.get(intArrayOf(0, 0, 0, 0), values)
                CosineMatcher.normalize(values)
            } finally {
                output.release()
            }
        } finally {
            blob.release()
            bgr.release()
        }
    }

    private fun scaledCrop(frame: Mat, box: Rect, scale: Float): Mat {
        val centerX = box.exactCenterX()
        val centerY = box.exactCenterY()
        val width = min(frame.width().toFloat(), box.width() * scale)
        val height = min(frame.height().toFloat(), box.height() * scale)
        var left = centerX - width / 2f
        var top = centerY - height / 2f
        left = left.coerceIn(0f, frame.width() - width)
        top = top.coerceIn(0f, frame.height() - height)
        val region = CvRect(left.toInt(), top.toInt(), max(1, width.toInt()), max(1, height.toInt()))
        val crop = frame.submat(region)
        val resized = Mat()
        try {
            Imgproc.resize(crop, resized, Size(80.0, 80.0))
            return resized
        } finally {
            crop.release()
        }
    }

    private fun boundedRect(box: Rect, width: Int, height: Int): CvRect? {
        val left = box.left.coerceIn(0, width - 1)
        val top = box.top.coerceIn(0, height - 1)
        val right = box.right.coerceIn(left + 1, width)
        val bottom = box.bottom.coerceIn(top + 1, height)
        if (right <= left || bottom <= top) return null
        return CvRect(left, top, right - left, bottom - top)
    }

    private fun softmax(output: Mat): FloatArray {
        val logits = FloatArray(output.total().toInt())
        output.get(0, 0, logits)
        val maximum = logits.max()
        val exponentials = logits.map { exp((it - maximum).toDouble()) }
        val total = exponentials.sum()
        return FloatArray(logits.size) { (exponentials[it] / total).toFloat() }
    }

    private fun Face.landmark(type: Int): PointF? = getLandmark(type)?.position

    private fun loadModel(assetName: String): Net {
        val target = File(appContext.codeCacheDir, assetName.substringAfterLast('/'))
        if (!target.exists() || target.length() != appContext.assets.open(assetName).use { it.available().toLong() }) {
            appContext.assets.open(assetName).use { input ->
                target.outputStream().use(input::copyTo)
            }
        }
        return Dnn.readNetFromONNX(target.absolutePath).also {
            it.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
            it.setPreferableTarget(Dnn.DNN_TARGET_CPU)
        }
    }

    override fun close() {
        detector.close()
        initialization.getOrNull()?.close()
    }

    private data class ImageQuality(val acceptable: Boolean)

    private sealed interface CaptureResult {
        data class Success(val embedding: FloatArray, val livenessScore: Float) : CaptureResult
        data class QualityRejected(val reason: String) : CaptureResult
        data object LivenessFailed : CaptureResult
        data class Unavailable(val reason: String) : CaptureResult
    }

    private data class Models(val recognition: Net, val passiveLiveness: List<Net>) : AutoCloseable {
        override fun close() = Unit
    }

    companion object {
        const val MODEL_VERSION = "intel-face-reidentification-retail-0095-onnx-v1"
        private const val RECOGNITION_ASSET = "models/face-reidentification-retail-0095.onnx"
        private val LIVENESS_ASSETS = listOf(
            "models/2.7_80x80_MiniFASNetV2.onnx",
            "models/4_0_0_80x80_MiniFASNetV1SE.onnx",
        )
        private const val RECOGNITION_SIZE = 128
        private const val LIVE_CLASS_INDEX = 1
        private const val MATCH_THRESHOLD = 0.72f
        private const val MINIMUM_MATCH_MARGIN = 0.08f
        private const val LIVENESS_THRESHOLD = 0.85f
        private const val MINIMUM_FACE_PIXELS = 140
        private const val MINIMUM_FACE_COVERAGE = 0.02
        private const val MAXIMUM_YAW_DEGREES = 25f
        private const val MAXIMUM_ROLL_DEGREES = 20f
        private const val MINIMUM_BRIGHTNESS = 35.0
        private const val MAXIMUM_BRIGHTNESS = 230.0
        private const val MINIMUM_LAPLACIAN_VARIANCE = 45.0
        private const val MAXIMUM_WORKING_DIMENSION = 1280
    }
}
