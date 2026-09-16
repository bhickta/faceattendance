package com.bhickta.faceattendance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bhickta.faceattendance.databinding.ActivityMainBinding
import com.bhickta.faceattendance.device.KioskController
import com.bhickta.faceattendance.device.DeviceConfigurationStore
import com.bhickta.faceattendance.vision.FaceCamera

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var kioskController: KioskController
    private var faceCamera: FaceCamera? = null

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
        super.onDestroy()
    }

    private fun startCamera() {
        faceCamera = FaceCamera(
            activity = this,
            previewView = binding.cameraPreview,
            onFaceCountChanged = { count ->
                binding.checkInButton.isEnabled = count == 1
                binding.faceStatus.setText(if (count == 1) R.string.face_ready else R.string.no_face)
            },
        ).also { it.start() }
    }

    private fun startCameraIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
}
