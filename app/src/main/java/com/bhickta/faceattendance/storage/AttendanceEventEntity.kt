package com.bhickta.faceattendance.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attendance_events",
    indices = [
        Index(value = ["deviceSequence"], unique = true),
        Index(value = ["syncState", "capturedAtEpochMillis"]),
        Index(value = ["personId", "capturedAtEpochMillis"]),
    ],
)
data class AttendanceEventEntity(
    @PrimaryKey val eventId: String,
    val deviceSequence: Long,
    val personId: String,
    val deviceId: String,
    val branchId: String,
    val gateId: String,
    val assignmentVersion: String,
    val direction: String,
    val capturedAtEpochMillis: Long,
    val capturedAtElapsedMillis: Long,
    val bootId: String,
    val timezoneOffsetMinutes: Int,
    val timestampConfidence: String,
    val matchScore: Double,
    val livenessScore: Double,
    val modelVersion: String,
    val templateVersion: String,
    val rosterVersion: String,
    val syncState: String = SyncState.PENDING.name,
    val syncAttempts: Int = 0,
    val lastSyncAttemptAtEpochMillis: Long? = null,
    val acknowledgedAtEpochMillis: Long? = null,
    val rejectionReason: String? = null,
)

enum class SyncState {
    PENDING,
    ACKNOWLEDGED,
    QUARANTINED,
    REJECTED,
}

@Entity(tableName = "device_state")
data class DeviceStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nextSequence: Long,
    val bootId: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
