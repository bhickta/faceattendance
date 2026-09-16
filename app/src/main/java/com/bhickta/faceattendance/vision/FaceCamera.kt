package com.bhickta.faceattendance.vision

import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
    private val onFaceCountChanged: (Int) -> Unit,
) : AutoCloseable {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.25f)
            .build(),
    )

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

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                    return@setAnalyzer
                }

                val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                detector.process(input)
                    .addOnSuccessListener { faces -> onFaceCountChanged(faces.size) }
                    .addOnCompleteListener { proxy.close() }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                activity,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(activity))
    }

    override fun close() {
        detector.close()
        analysisExecutor.shutdown()
    }
}
