package com.bhickta.faceattendance.attendance

enum class AttendanceDirection {
    IN,
    OUT,
}

enum class TimestampConfidence {
    TRUSTED,
    UNVERIFIED,
    CLOCK_CHANGED,
}

data class RecognitionEvidence(
    val personId: String,
    val matchScore: Double,
    val livenessScore: Double,
    val modelVersion: String,
    val templateVersion: String,
    val rosterVersion: String,
)

data class DeviceAssignment(
    val deviceId: String,
    val branchId: String,
    val gateId: String,
    val assignmentVersion: String,
)
