package com.bhickta.faceattendance

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bhickta.faceattendance.databinding.ActivityEnrollmentBinding
import com.bhickta.faceattendance.device.DeviceConfigurationStore
import com.bhickta.faceattendance.device.DeviceKeyManager
import com.bhickta.faceattendance.enrollment.EnrollmentClient
import com.bhickta.faceattendance.sync.AttendanceApiClient
import com.bhickta.faceattendance.vision.BiometricEngine
import com.bhickta.faceattendance.vision.BiometricEngineFactory
import com.bhickta.faceattendance.vision.BiometricRosterStore
import com.bhickta.faceattendance.vision.EnrollmentAggregator
import com.bhickta.faceattendance.vision.EnrollmentResult
import com.bhickta.faceattendance.vision.FaceCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnrollmentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnrollmentBinding
    private lateinit var biometricEngine: BiometricEngine
    private var faceCamera: FaceCamera? = null
    private val samples = mutableListOf<FloatArray>()
    private var faceReady = false
    private var busy = false

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else binding.enrollmentStatus.setText(R.string.camera_permission_required)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        biometricEngine = BiometricEngineFactory.create(this)
        binding.captureSampleButton.setOnClickListener { captureSample() }
        if (!biometricEngine.isEnrollmentReady) {
            binding.enrollmentStatus.setText(R.string.biometric_model_unavailable)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        updateButton()
    }

    override fun onDestroy() {
        faceCamera?.close()
        biometricEngine.close()
        super.onDestroy()
    }

    private fun startCamera() {
        faceCamera = FaceCamera(
            activity = this,
            previewView = binding.enrollmentCamera,
            onFaceObserved = { observation ->
                runOnUiThread {
                    faceReady = observation.faceCount == 1
                    if (!busy && !faceReady) binding.enrollmentStatus.setText(R.string.enrollment_position_face)
                    updateButton()
                }
            },
        ).also { it.start() }
    }

    private fun captureSample() {
        if (busy || !faceReady) return
        if (binding.enrollmentToken.text?.toString().isNullOrBlank()) {
            binding.enrollmentStatus.setText(R.string.enrollment_token_required)
            return
        }
        busy = true
        updateButton()
        binding.enrollmentStatus.setText(R.string.enrollment_checking_sample)
        faceCamera?.capture(
            onSuccess = { bitmap ->
                lifecycleScope.launch {
                    val result = try {
                        withContext(Dispatchers.Default) { biometricEngine.enroll(bitmap) }
                    } finally {
                        bitmap.recycle()
                    }
                    handleSample(result)
                }
            },
            onFailure = {
                runOnUiThread {
                    busy = false
                    binding.enrollmentStatus.setText(R.string.capture_failed)
                    updateButton()
                }
            },
        )
    }

    private suspend fun handleSample(result: EnrollmentResult) {
        when (result) {
            is EnrollmentResult.Sample -> {
                samples += result.embedding
                if (samples.size < EnrollmentAggregator.REQUIRED_SAMPLES) {
                    binding.enrollmentStatus.text = getString(
                        R.string.enrollment_sample_saved,
                        samples.size,
                        EnrollmentAggregator.REQUIRED_SAMPLES,
                    )
                    busy = false
                    updateButton()
                } else {
                    submitEnrollment()
                }
            }
            EnrollmentResult.QualityRejected -> finishSample(R.string.enrollment_quality_rejected)
            EnrollmentResult.LivenessFailed -> finishSample(R.string.liveness_failed)
            is EnrollmentResult.Unavailable -> {
                binding.enrollmentStatus.text = getString(R.string.biometric_unavailable, result.reason)
                busy = false
                updateButton()
            }
        }
    }

    private suspend fun submitEnrollment() {
        val aggregated = EnrollmentAggregator.aggregate(samples)
        if (aggregated == null) {
            samples.clear()
            finishSample(R.string.enrollment_inconsistent)
            return
        }
        val configuration = DeviceConfigurationStore(this).get()
        if (configuration == null) {
            finishSample(R.string.device_not_provisioned)
            return
        }
        binding.enrollmentStatus.setText(R.string.enrollment_saving)
        val token = binding.enrollmentToken.text?.toString().orEmpty()
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                val keyManager = DeviceKeyManager()
                EnrollmentClient(configuration, keyManager).submit(token, aggregated.embedding)
                val store = BiometricRosterStore(this@EnrollmentActivity)
                AttendanceApiClient(configuration, keyManager)
                    .syncRoster(store.get()?.version)
                    .roster
                    ?.let(store::save)
            }
        }
        outcome.onSuccess {
            binding.enrollmentToken.text?.clear()
            binding.enrollmentStatus.setText(R.string.enrollment_complete)
            setResult(Activity.RESULT_OK)
            binding.captureSampleButton.postDelayed({ finish() }, 1_500)
        }.onFailure {
            samples.clear()
            binding.enrollmentStatus.text = getString(R.string.enrollment_failed, it.message)
            busy = false
            updateButton()
        }
    }

    private fun finishSample(message: Int) {
        binding.enrollmentStatus.setText(message)
        busy = false
        updateButton()
    }

    private fun updateButton() {
        binding.captureSampleButton.isEnabled = faceReady && !busy && biometricEngine.isEnrollmentReady
        binding.captureSampleButton.text = getString(
            R.string.capture_enrollment_sample,
            samples.size + 1,
            EnrollmentAggregator.REQUIRED_SAMPLES,
        )
    }
}
