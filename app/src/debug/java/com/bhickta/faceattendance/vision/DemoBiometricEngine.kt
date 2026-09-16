package com.bhickta.faceattendance.vision

import android.graphics.Bitmap
import com.bhickta.faceattendance.attendance.RecognitionEvidence

/** Debug-only deterministic engine for exercising the attendance pipeline without biometric claims. */
class DemoBiometricEngine : BiometricEngine {
    override val isReady = true

    override suspend fun identify(bitmap: Bitmap): BiometricResult = BiometricResult.Match(
        evidence = RecognitionEvidence(
            personId = "DEMO-EMPLOYEE",
            matchScore = 1.0,
            livenessScore = 1.0,
            modelVersion = "debug-no-biometric-model",
            templateVersion = "debug-template",
            rosterVersion = "debug-roster",
        ),
        displayName = "DEMO EMPLOYEE",
    )
}
