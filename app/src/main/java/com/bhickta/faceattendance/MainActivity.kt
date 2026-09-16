package com.bhickta.faceattendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.bhickta.faceattendance.storage.SyncState
import com.bhickta.faceattendance.sync.SyncScheduler
import com.bhickta.faceattendance.ui.applyCompatInsets
import com.bhickta.faceattendance.vision.BiometricEngine
import com.bhickta.faceattendance.vision.BiometricEngineFactory
import com.bhickta.faceattendance.vision.BiometricResult
import com.bhickta.faceattendance.vision.ActiveLivenessChallenge
import com.bhickta.faceattendance.vision.ChallengeUpdate
import com.bhickta.faceattendance.vision.FaceCamera
import com.bhickta.faceattendance.vision.FaceObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var kioskController: KioskController
    private lateinit var biometricEngine: BiometricEngine
    private var faceCamera: FaceCamera? = null
    private var faceReady = false
    private var processing = false
    private var activeChallenge: ActiveLivenessChallenge? = null
    private var pendingDirection: AttendanceDirection? = null
    private var blinkFallbackScheduled = false
    private var resultHideJob: Job? = null
    private var toneGenerator: ToneGenerator? = null
    private var livenessAttempts = 0

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
        applyCompatInsets(binding.root, applyTop = true, applyBottom = true)
        biometricEngine = BiometricEngineFactory.create(this)
        binding.checkInButton.setOnClickListener { punch(AttendanceDirection.IN) }
        binding.checkOutButton.setOnClickListener { punch(AttendanceDirection.OUT) }
        binding.enrollButton.setOnClickListener {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }

        kioskController = KioskController(this)
        kioskController.applyDedicatedDevicePolicy()
        val managedDevice = kioskController.isDeviceOwner
        binding.kioskStatus.setText(
            if (managedDevice) R.string.device_owner_active else R.string.not_device_owner,
        )
        binding.kioskStatus.setTextColor(
            ContextCompat.getColor(this, if (managedDevice) R.color.success else R.color.warning),
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
        resultHideJob?.cancel()
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    private fun startCamera() {
        faceCamera = FaceCamera(
            activity = this,
            previewView = binding.cameraPreview,
            onFaceObserved = { observation ->
                runOnUiThread {
                    faceReady = observation.faceCount == 1
                    if (!processing) {
                        binding.faceStatus.setText(
                            if (observation.faceCount == 1) R.string.face_ready else R.string.no_face,
                        )
                    }
                    processChallenge(observation)
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
        binding.enrollButton.isEnabled = !processing && configuration != null && biometricEngine.isEnrollmentReady
        if (configuration != null && !ClockTrust.hasValidAuthorization(configuration)) {
            binding.checkInButton.isEnabled = false
            binding.checkOutButton.isEnabled = false
            binding.faceStatus.setText(R.string.authorization_expired)
        }
    }

    private fun punch(direction: AttendanceDirection) {
        if (processing || !faceReady) return
        processing = true
        pendingDirection = direction
        blinkFallbackScheduled = false
        livenessAttempts = 0
        binding.daySummary.visibility = View.GONE
        hideResult()
        val challenge = ActiveLivenessChallenge()
        activeChallenge = challenge
        updateButtons()
        binding.faceStatus.setText(R.string.look_straight)
        lifecycleScope.launch {
            delay(ACTIVE_CHALLENGE_TIMEOUT_MS)
            if (activeChallenge === challenge) {
                activeChallenge = null
                pendingDirection = null
                finishPunch(R.string.liveness_failed)
            }
        }
    }

    private fun processChallenge(observation: FaceObservation) {
        val challenge = activeChallenge ?: return
        when (
            challenge.observe(
                observation.faceCount,
                observation.yawDegrees,
                observation.eyesOpenProbability,
            )
        ) {
            ChallengeUpdate.WaitingForNeutral -> binding.faceStatus.setText(R.string.look_straight)
            ChallengeUpdate.RequestBlink -> {
                binding.faceStatus.setText(R.string.blink_now)
                scheduleBlinkFallback(challenge)
            }
            ChallengeUpdate.WaitingForBlink -> Unit
            ChallengeUpdate.Passed -> {
                val direction = pendingDirection ?: return
                activeChallenge = null
                pendingDirection = null
                captureForRecognition(direction)
            }
        }
    }

    private fun scheduleBlinkFallback(challenge: ActiveLivenessChallenge) {
        if (blinkFallbackScheduled) return
        blinkFallbackScheduled = true
        lifecycleScope.launch {
            delay(BLINK_FALLBACK_MS)
            if (activeChallenge === challenge) challenge.skipBlink()
        }
    }

    private fun captureForRecognition(direction: AttendanceDirection) {
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
                    showResult(ResultKind.ERROR, R.string.capture_failed)
                    processing = false
                    updateButtons()
                }
            },
        )
    }

    private suspend fun handleBiometricResult(result: BiometricResult, direction: AttendanceDirection) {
        when (result) {
            is BiometricResult.Match -> savePunch(result, direction)
            BiometricResult.NoMatch -> offerSelfRegistration()
            BiometricResult.LivenessFailed -> retryOrFailLiveness(direction)
            is BiometricResult.Unavailable -> {
                binding.faceStatus.text = getString(R.string.biometric_unavailable, result.reason)
                showResult(ResultKind.WARNING, R.string.biometric_unavailable, result.reason)
                processing = false
                updateButtons()
            }
        }
    }

    private suspend fun retryOrFailLiveness(direction: AttendanceDirection) {
        if (livenessAttempts < MAX_LIVENESS_ATTEMPTS) {
            livenessAttempts += 1
            binding.faceStatus.setText(R.string.liveness_retry)
            showResult(ResultKind.WARNING, R.string.liveness_retry, null, visibleMillis = 2_500L)
            delay(300L)
            captureForRecognition(direction)
        } else {
            finishPunch(R.string.liveness_failed, ResultKind.ERROR)
        }
    }

    private fun offerSelfRegistration() {
        val direction = pendingDirection ?: AttendanceDirection.IN
        activeChallenge = null
        pendingDirection = null
        processing = false
        updateButtons()
        binding.faceStatus.setText(R.string.identity_not_found)
        showResult(
            ResultKind.WARNING,
            R.string.identity_not_found,
            null,
            R.string.register_new_employee,
            {
                startActivity(
                    Intent(this, SelfRegistrationActivity::class.java).putExtra(
                        SelfRegistrationActivity.EXTRA_DIRECTION,
                        direction.name,
                    ),
                )
            },
            SELF_REGISTRATION_VISIBLE_MS,
        )
    }

    private suspend fun savePunch(match: BiometricResult.Match, direction: AttendanceDirection) {
        val configuration = DeviceConfigurationStore(this).get()
        if (configuration == null) {
            finishPunch(R.string.device_not_provisioned, ResultKind.WARNING)
            return
        }
        if (!ClockTrust.hasValidAuthorization(configuration)) {
            finishPunch(R.string.authorization_expired, ResultKind.WARNING)
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
            val title = if (direction == AttendanceDirection.IN) {
                R.string.result_check_in
            } else {
                R.string.result_check_out
            }
            binding.faceStatus.setText(
                getString(R.string.attendance_saved, direction.name, match.displayName),
            )
            val summary = loadDaySummary(match.evidence.personId)
            if (summary != null) {
                binding.daySummary.text = summary
                binding.daySummary.visibility = View.VISIBLE
            }
            showResult(ResultKind.SUCCESS, title, summary ?: match.displayName)
        }.onFailure {
            val duplicate = it === DuplicatePunchException
            val message = if (duplicate) R.string.duplicate_punch else R.string.attendance_save_failed
            binding.faceStatus.setText(message)
            showResult(if (duplicate) ResultKind.WARNING else ResultKind.ERROR, message)
        }
        processing = false
        updateButtons()
    }

    private suspend fun loadDaySummary(personId: String): String? {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = AttendanceDatabase.get(this)
            .attendanceEventDao()
            .forPersonSince(personId, startOfDay)
            .filter { it.syncState != SyncState.REJECTED.name }
        if (events.isEmpty()) return null

        var totalMillis = 0L
        var openIn: Long? = null
        var firstIn: Long? = null
        var lastOut: Long? = null
        for (event in events) {
            if (event.direction == AttendanceDirection.IN.name) {
                if (openIn == null) {
                    openIn = event.capturedAtEpochMillis
                    if (firstIn == null) firstIn = event.capturedAtEpochMillis
                }
            } else {
                lastOut = event.capturedAtEpochMillis
                val opened = openIn
                if (opened != null) {
                    totalMillis += (event.capturedAtEpochMillis - opened).coerceAtLeast(0L)
                    openIn = null
                }
            }
        }
        openIn?.let { totalMillis += (System.currentTimeMillis() - it).coerceAtLeast(0L) }

        val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")
        fun format(millis: Long?): String =
            millis?.let { Instant.ofEpochMilli(it).atZone(zone).format(timeFormat) }.orEmpty()
        val total = formatDuration(totalMillis)
        return when {
            firstIn != null && lastOut != null && lastOut >= firstIn ->
                getString(R.string.day_summary, format(firstIn), format(lastOut), total)
            firstIn != null -> getString(R.string.day_summary_open, format(firstIn), total)
            lastOut != null -> getString(R.string.day_summary_out, format(lastOut), total)
            else -> null
        }
    }

    private fun formatDuration(millis: Long): String {
        val minutes = (millis / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (hours > 0) {
            getString(R.string.duration_hm, hours, remainder)
        } else {
            getString(R.string.duration_m, remainder)
        }
    }

    private fun finishPunch(
        message: Int,
        kind: ResultKind = ResultKind.ERROR,
        detail: CharSequence? = null,
    ) {
        activeChallenge = null
        pendingDirection = null
        binding.faceStatus.setText(message)
        showResult(kind, message, detail)
        processing = false
        updateButtons()
    }

    private enum class ResultKind { SUCCESS, WARNING, ERROR }

    private fun showResult(
        kind: ResultKind,
        titleRes: Int,
        detail: CharSequence? = null,
        actionLabelRes: Int = 0,
        onAction: (() -> Unit)? = null,
        visibleMillis: Long = RESULT_VISIBLE_MS,
    ) {
        val accentRes = when (kind) {
            ResultKind.SUCCESS -> R.color.success
            ResultKind.WARNING -> R.color.warning
            ResultKind.ERROR -> R.color.error
        }
        val backgroundRes = when (kind) {
            ResultKind.SUCCESS -> R.color.result_success_background
            ResultKind.WARNING -> R.color.result_warning_background
            ResultKind.ERROR -> R.color.result_error_background
        }
        val accent = ContextCompat.getColor(this, accentRes)
        binding.resultIcon.setImageResource(
            when (kind) {
                ResultKind.SUCCESS -> R.drawable.ic_check
                ResultKind.WARNING -> R.drawable.ic_warning
                ResultKind.ERROR -> R.drawable.ic_close
            },
        )
        binding.resultIcon.backgroundTintList = ColorStateList.valueOf(accent)
        binding.resultCard.setCardBackgroundColor(ContextCompat.getColor(this, backgroundRes))
        binding.resultTitle.setText(titleRes)
        binding.resultTitle.setTextColor(accent)
        if (detail.isNullOrBlank()) {
            binding.resultDetail.visibility = View.GONE
        } else {
            binding.resultDetail.visibility = View.VISIBLE
            binding.resultDetail.text = detail
        }
        if (actionLabelRes != 0 && onAction != null) {
            binding.resultAction.visibility = View.VISIBLE
            binding.resultAction.setText(actionLabelRes)
            binding.resultAction.setOnClickListener {
                hideResult()
                onAction()
            }
        } else {
            binding.resultAction.visibility = View.GONE
            binding.resultAction.setOnClickListener(null)
        }

        binding.resultCard.animate().cancel()
        binding.resultCard.visibility = View.VISIBLE
        binding.resultCard.alpha = 0f
        binding.resultCard.scaleX = 0.96f
        binding.resultCard.scaleY = 0.96f
        binding.resultCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()

        playFeedbackTone(kind)
        playFeedbackHaptic(kind)

        resultHideJob?.cancel()
        resultHideJob = lifecycleScope.launch {
            delay(visibleMillis)
            binding.resultCard.animate().alpha(0f).setDuration(220L).withEndAction {
                binding.resultCard.visibility = View.GONE
            }.start()
        }
    }

    private fun hideResult() {
        resultHideJob?.cancel()
        resultHideJob = null
        binding.resultCard.animate().cancel()
        binding.resultCard.visibility = View.GONE
    }

    private fun playFeedbackTone(kind: ResultKind) {
        runCatching {
            val tone = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { toneGenerator = it }
            tone.startTone(
                when (kind) {
                    ResultKind.SUCCESS -> ToneGenerator.TONE_PROP_ACK
                    ResultKind.WARNING -> ToneGenerator.TONE_PROP_BEEP
                    ResultKind.ERROR -> ToneGenerator.TONE_PROP_NACK
                },
                if (kind == ResultKind.SUCCESS) 180 else 320,
            )
        }
    }

    private fun playFeedbackHaptic(kind: ResultKind) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = when (kind) {
            ResultKind.SUCCESS -> VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)
            ResultKind.WARNING -> VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE)
            ResultKind.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 60, 60, 60), -1)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    private companion object {
        const val ACTIVE_CHALLENGE_TIMEOUT_MS = 25_000L
        const val BLINK_FALLBACK_MS = 8_000L
        const val RESULT_VISIBLE_MS = 6_000L
        const val SELF_REGISTRATION_VISIBLE_MS = 15_000L
        const val MAX_LIVENESS_ATTEMPTS = 2
    }
}
