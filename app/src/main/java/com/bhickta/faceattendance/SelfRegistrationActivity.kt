package com.bhickta.faceattendance

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bhickta.faceattendance.attendance.AttendanceDirection
import com.bhickta.faceattendance.databinding.ActivitySelfRegistrationBinding
import com.bhickta.faceattendance.device.DeviceConfigurationStore
import com.bhickta.faceattendance.device.DeviceKeyManager
import com.bhickta.faceattendance.sync.AttendanceApiClient
import com.bhickta.faceattendance.ui.applyCompatInsets
import com.bhickta.faceattendance.vision.BiometricEngine
import com.bhickta.faceattendance.vision.BiometricEngineFactory
import com.bhickta.faceattendance.vision.EnrollmentAggregator
import com.bhickta.faceattendance.vision.EnrollmentResult
import com.bhickta.faceattendance.vision.FaceCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelfRegistrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySelfRegistrationBinding
    private lateinit var biometricEngine: BiometricEngine
    private var faceCamera: FaceCamera? = null
    private val samples = mutableListOf<FloatArray>()
    private var faceReady = false
    private var busy = false

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.selfRegistrationStatus.setText(R.string.camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelfRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyCompatInsets(binding.root, applyTop = true, applyBottom = true, includeIme = true)
        biometricEngine = BiometricEngineFactory.create(this)
        binding.selfRegistrationCapture.setOnClickListener { captureSample() }
        if (!biometricEngine.isEnrollmentReady) {
            binding.selfRegistrationStatus.setText(R.string.biometric_model_unavailable)
        } else if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
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
            previewView = binding.selfRegistrationCamera,
            onFaceObserved = { observation ->
                runOnUiThread {
                    faceReady = observation.faceCount == 1
                    if (!busy && !faceReady) {
                        binding.selfRegistrationStatus.setText(R.string.self_registration_position)
                    }
                    updateButton()
                }
            },
        ).also { it.start() }
    }

    private fun captureSample() {
        if (busy || !faceReady) return
        if (binding.fullName.text?.toString()?.isBlank() != false) {
            binding.selfRegistrationStatus.setText(R.string.self_registration_name_required)
            return
        }
        if (!binding.consent.isChecked) {
            binding.selfRegistrationStatus.setText(R.string.self_registration_consent_required)
            return
        }
        busy = true
        updateButton()
        binding.selfRegistrationStatus.setText(R.string.enrollment_checking_sample)
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
                    binding.selfRegistrationStatus.setText(R.string.capture_failed)
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
                    binding.selfRegistrationStatus.text = getString(
                        R.string.self_registration_sample_saved,
                        samples.size,
                        EnrollmentAggregator.REQUIRED_SAMPLES,
                    )
                    busy = false
                    updateButton()
                } else {
                    submit()
                }
            }
            is EnrollmentResult.QualityRejected ->
                finishSample(R.string.enrollment_quality_rejected_detail, result.reason)
            EnrollmentResult.LivenessFailed -> finishSample(R.string.liveness_failed)
            is EnrollmentResult.Unavailable -> {
                binding.selfRegistrationStatus.text =
                    getString(R.string.biometric_unavailable, result.reason)
                busy = false
                updateButton()
            }
        }
    }

    private suspend fun submit() {
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
        binding.selfRegistrationStatus.setText(R.string.self_registration_saving)
        val fullName = binding.fullName.text?.toString()?.trim().orEmpty()
        val phone = binding.phone.text?.toString()?.trim().orEmpty()
        val direction = intent.getStringExtra(EXTRA_DIRECTION) ?: AttendanceDirection.IN.name
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                AttendanceApiClient(configuration, DeviceKeyManager()).submitSelfRegistration(
                    fullName = fullName,
                    phone = phone,
                    direction = direction,
                    capturedAtEpochMillis = System.currentTimeMillis(),
                    sampleCount = samples.size,
                    embedding = aggregated.embedding,
                )
            }
        }
        outcome.onSuccess { result ->
            val message = when {
                result.status == "already_enrolled" ->
                    getString(R.string.self_registration_already, result.employeeName.orEmpty())
                result.employeeName != null -> getString(R.string.self_registration_pending)
                else -> getString(R.string.self_registration_sent)
            }
            binding.selfRegistrationStatus.text = message
            setResult(Activity.RESULT_OK)
            binding.selfRegistrationCapture.postDelayed({ finish() }, 2_500)
        }.onFailure {
            samples.clear()
            binding.selfRegistrationStatus.text =
                getString(R.string.self_registration_failed, it.message)
            busy = false
            updateButton()
        }
    }

    private fun finishSample(message: Int, vararg formatArgs: Any) {
        binding.selfRegistrationStatus.text = getString(message, *formatArgs)
        busy = false
        updateButton()
    }

    private fun updateButton() {
        binding.selfRegistrationCapture.isEnabled =
            faceReady && !busy && biometricEngine.isEnrollmentReady
        binding.selfRegistrationCapture.text = getString(
            R.string.capture_enrollment_sample,
            samples.size + 1,
            EnrollmentAggregator.REQUIRED_SAMPLES,
        )
    }

    companion object {
        const val EXTRA_DIRECTION = "direction"
    }
}
