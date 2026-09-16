package com.bhickta.faceattendance.vision

import android.graphics.Bitmap
import android.content.Context
import com.bhickta.faceattendance.BuildConfig
import com.bhickta.faceattendance.attendance.RecognitionEvidence

interface BiometricEngine : AutoCloseable {
    val isReady: Boolean
    val isEnrollmentReady: Boolean
        get() = isReady

    suspend fun identify(bitmap: Bitmap): BiometricResult

    suspend fun enroll(bitmap: Bitmap): EnrollmentResult =
        EnrollmentResult.Unavailable("Enrollment is not supported")

    override fun close() = Unit
}

sealed interface EnrollmentResult {
    data class Sample(val embedding: FloatArray, val livenessScore: Double) : EnrollmentResult
    data class QualityRejected(val reason: String) : EnrollmentResult
    data object LivenessFailed : EnrollmentResult
    data class Unavailable(val reason: String) : EnrollmentResult
}

sealed interface BiometricResult {
    data class Match(
        val evidence: RecognitionEvidence,
        val displayName: String,
    ) : BiometricResult

    data object NoMatch : BiometricResult
    data object LivenessFailed : BiometricResult
    data class Unavailable(val reason: String) : BiometricResult
}

object BiometricEngineFactory {
    fun create(context: Context): BiometricEngine {
        val className = BuildConfig.BIOMETRIC_ENGINE_CLASS
        if (className.isBlank()) return UnavailableBiometricEngine
        return runCatching {
            val type = Class.forName(className)
            runCatching {
                type.getDeclaredConstructor(Context::class.java)
                    .newInstance(context.applicationContext) as BiometricEngine
            }.getOrElse {
                type.getDeclaredConstructor().newInstance() as BiometricEngine
            }
        }.getOrElse { UnavailableBiometricEngine }
    }
}

private object UnavailableBiometricEngine : BiometricEngine {
    override val isReady = false

    override suspend fun identify(bitmap: Bitmap) =
        BiometricResult.Unavailable("Offline biometric engine is not available")
}
