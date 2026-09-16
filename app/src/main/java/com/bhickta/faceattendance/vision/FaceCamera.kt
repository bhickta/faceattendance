package com.bhickta.faceattendance.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceCamera(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val onFaceObserved: (FaceObservation) -> Unit,
) : AutoCloseable {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.25f)
            .build(),
    )
    private var imageCapture: ImageCapture? = null

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val captureUseCase = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = captureUseCase

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                    return@setAnalyzer
                }

                val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                detector.process(input)
                    .addOnSuccessListener { faces ->
                        val face = faces.singleOrNull()
                        val left = face?.leftEyeOpenProbability
                        val right = face?.rightEyeOpenProbability
                        onFaceObserved(
                            FaceObservation(
                                faceCount = faces.size,
                                yawDegrees = face?.headEulerAngleY,
                                eyesOpenProbability = if (left != null && right != null) {
                                    minOf(left, right)
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                    .addOnCompleteListener { proxy.close() }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                activity,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
                captureUseCase,
            )
        }, ContextCompat.getMainExecutor(activity))
    }

    fun capture(onSuccess: (Bitmap) -> Unit, onFailure: (Throwable) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onFailure(IllegalStateException("Camera is not ready"))
            return
        }
        capture.takePicture(
            analysisExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes.first().buffer
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("Camera returned an unreadable image")
                        val rotation = image.imageInfo.rotationDegrees.toFloat()
                        val bitmap = if (rotation == 0f) decoded else Bitmap.createBitmap(
                            decoded,
                            0,
                            0,
                            decoded.width,
                            decoded.height,
                            Matrix().apply { postRotate(rotation) },
                            true,
                        )
                        onSuccess(bitmap)
                    } catch (error: Throwable) {
                        onFailure(error)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) = onFailure(exception)
            },
        )
    }

    override fun close() {
        imageCapture = null
        detector.close()
        analysisExecutor.shutdown()
    }
}

data class FaceObservation(
    val faceCount: Int,
    val yawDegrees: Float?,
    val eyesOpenProbability: Float? = null,
)
