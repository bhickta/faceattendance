package com.bhickta.faceattendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bhickta.faceattendance.attendance.AttendanceDirection
import com.bhickta.faceattendance.attendance.AttendanceRepository
import com.bhickta.faceattendance.attendance.DeviceAssignment
import com.bhickta.faceattendance.attendance.TimestampConfidence
import com.bhickta.faceattendance.databinding.ActivityMainBinding
import com.bhickta.faceattendance.device.DeviceConfigurationStore
import com.bhickta.faceattendance.device.ClockTrust
import com.bhickta.faceattendance.device.KioskController
import com.bhickta.faceattendance.storage.AttendanceDatabase
import com.bhickta.faceattendance.storage.DuplicatePunchException
import com.bhickta.faceattendance.sync.SyncScheduler
import com.bhickta.faceattendance.vision.BiometricEngine
import com.bhickta.faceattendance.vision.BiometricEngineFactory
import com.bhickta.faceattendance.vision.BiometricResult
import com.bhickta.faceattendance.vision.FaceCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var kioskController: KioskController
    private lateinit var biometricEngine: BiometricEngine
    private var faceCamera: FaceCamera? = null
    private var faceReady = false
    private var processing = false

    private val provisionDevice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) startCameraIfPermitted()
        else binding.faceStatus.setText(R.string.device_not_provisioned)
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else binding.faceStatus.setText(R.string.camera_permission_required)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        biometricEngine = BiometricEngineFactory.create()
        binding.checkInButton.setOnClickListener { punch(AttendanceDirection.IN) }
        binding.checkOutButton.setOnClickListener { punch(AttendanceDirection.OUT) }

        kioskController = KioskController(this)
        kioskController.applyDedicatedDevicePolicy()
        binding.kioskStatus.setText(
            if (kioskController.isDeviceOwner) R.string.device_owner_active
            else R.string.not_device_owner,
        )

        if (DeviceConfigurationStore(this).get() == null) {
            binding.faceStatus.setText(R.string.device_not_provisioned)
            provisionDevice.launch(Intent(this, ProvisioningActivity::class.java))
        } else {
            startCameraIfPermitted()
        }
    }

    override fun onResume() {
        super.onResume()
        kioskController.enterLockTaskIfAllowed()
    }

    override fun onDestroy() {
        faceCamera?.close()
        biometricEngine.close()
        super.onDestroy()
    }

    private fun startCamera() {
        faceCamera = FaceCamera(
            activity = this,
            previewView = binding.cameraPreview,
            onFaceCountChanged = { count ->
                runOnUiThread {
                    faceReady = count == 1
                    binding.faceStatus.setText(if (count == 1) R.string.face_ready else R.string.no_face)
                    updateButtons()
                }
            },
        ).also { it.start() }
        updateButtons()
    }

    private fun startCameraIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateButtons() {
        val configuration = DeviceConfigurationStore(this).get()
        val enabled = faceReady && !processing && biometricEngine.isReady && configuration != null
        val mode = configuration?.directionMode ?: "SELECT"
        binding.checkInButton.visibility = if (mode == "OUT") View.GONE else View.VISIBLE
        binding.checkOutButton.visibility = if (mode == "IN") View.GONE else View.VISIBLE
        binding.checkInButton.isEnabled = enabled
        binding.checkOutButton.isEnabled = enabled
        if (configuration != null && !ClockTrust.hasValidAuthorization(configuration)) {
            binding.checkInButton.isEnabled = false
            binding.checkOutButton.isEnabled = false
            binding.faceStatus.setText(R.string.authorization_expired)
        }
        if (BuildConfig.DEBUG && biometricEngine.isReady) {
            binding.kioskStatus.setText(R.string.debug_biometric_warning)
        }
    }

    private fun punch(direction: AttendanceDirection) {
        if (processing || !faceReady) return
        processing = true
        updateButtons()
        binding.faceStatus.setText(R.string.recognizing)
        faceCamera?.capture(
            onSuccess = { bitmap ->
                lifecycleScope.launch {
                    val result = try {
                        withContext(Dispatchers.Default) { biometricEngine.identify(bitmap) }
                    } finally {
                        bitmap.recycle()
                    }
                    handleBiometricResult(result, direction)
                }
            },
            onFailure = {
                runOnUiThread {
                    binding.faceStatus.setText(R.string.capture_failed)
                    processing = false
                    updateButtons()
                }
            },
        )
    }

    private suspend fun handleBiometricResult(result: BiometricResult, direction: AttendanceDirection) {
        when (result) {
            is BiometricResult.Match -> savePunch(result, direction)
            BiometricResult.NoMatch -> finishPunch(R.string.identity_not_found)
            BiometricResult.LivenessFailed -> finishPunch(R.string.liveness_failed)
            is BiometricResult.Unavailable -> {
                binding.faceStatus.text = getString(R.string.biometric_unavailable, result.reason)
                processing = false
                updateButtons()
            }
        }
    }

    private suspend fun savePunch(match: BiometricResult.Match, direction: AttendanceDirection) {
        val configuration = DeviceConfigurationStore(this).get()
        if (configuration == null) {
            finishPunch(R.string.device_not_provisioned)
            return
        }
        if (!ClockTrust.hasValidAuthorization(configuration)) {
            finishPunch(R.string.authorization_expired)
            return
        }
        val confidence = ClockTrust.timestampConfidence(contentResolver, configuration)
        val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                AttendanceRepository(
                    AttendanceDatabase.get(this@MainActivity).attendanceEventDao(),
                ).record(
                    evidence = match.evidence,
                    assignment = DeviceAssignment(
                        deviceId = configuration.deviceId,
                        branchId = configuration.branchId,
                        gateId = configuration.gateId,
                        assignmentVersion = configuration.assignmentVersion,
                    ),
                    direction = direction,
                    bootId = "boot-$bootCount",
                    timestampConfidence = confidence,
                )
            }
        }
        outcome.onSuccess {
            SyncScheduler.requestNow(this)
            binding.faceStatus.text = getString(
                R.string.attendance_saved,
                direction.name,
                match.displayName,
            )
        }.onFailure {
            binding.faceStatus.setText(
                if (it === DuplicatePunchException) R.string.duplicate_punch else R.string.attendance_save_failed,
            )
        }
        processing = false
        updateButtons()
    }

    private fun finishPunch(message: Int) {
        binding.faceStatus.setText(message)
        processing = false
        updateButtons()
    }
}
