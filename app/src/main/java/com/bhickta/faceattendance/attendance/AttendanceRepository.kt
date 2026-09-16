package com.bhickta.faceattendance.attendance

import android.os.SystemClock
import com.bhickta.faceattendance.storage.AttendanceEventDao
import com.bhickta.faceattendance.storage.AttendanceEventEntity
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class AttendanceRepository(
    private val dao: AttendanceEventDao,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedClockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val eventId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun record(
        evidence: RecognitionEvidence,
        assignment: DeviceAssignment,
        direction: AttendanceDirection,
        bootId: String,
        timestampConfidence: TimestampConfidence,
    ): AttendanceEventEntity {
        require(evidence.personId.isNotBlank()) { "personId is required" }
        require(assignment.deviceId.isNotBlank()) { "deviceId is required" }
        require(evidence.matchScore in 0.0..1.0) { "matchScore must be between 0 and 1" }
        require(evidence.livenessScore in 0.0..1.0) { "livenessScore must be between 0 and 1" }

        val capturedAt = wallClockMillis()
        val offsetMinutes = ZoneId.systemDefault().rules
            .getOffset(Instant.ofEpochMilli(capturedAt)).totalSeconds / 60

        return dao.insertWithNextSequence(
            bootId = bootId,
            personId = evidence.personId,
            direction = direction.name,
            capturedAtEpochMillis = capturedAt,
            cooldownMillis = DUPLICATE_COOLDOWN_MILLIS,
        ) { sequence ->
            AttendanceEventEntity(
                eventId = eventId(),
                deviceSequence = sequence,
                personId = evidence.personId,
                deviceId = assignment.deviceId,
                branchId = assignment.branchId,
                gateId = assignment.gateId,
                assignmentVersion = assignment.assignmentVersion,
                direction = direction.name,
                capturedAtEpochMillis = capturedAt,
                capturedAtElapsedMillis = elapsedClockMillis(),
                bootId = bootId,
                timezoneOffsetMinutes = offsetMinutes,
                timestampConfidence = timestampConfidence.name,
                matchScore = evidence.matchScore,
                livenessScore = evidence.livenessScore,
                modelVersion = evidence.modelVersion,
                templateVersion = evidence.templateVersion,
                rosterVersion = evidence.rosterVersion,
            )
        }
    }

    private companion object {
        const val DUPLICATE_COOLDOWN_MILLIS = 30_000L
    }
}
