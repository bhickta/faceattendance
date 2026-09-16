package com.bhickta.faceattendance.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class AttendanceEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvent(event: AttendanceEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertState(state: DeviceStateEntity)

    @Query("SELECT * FROM device_state WHERE id = 1")
    protected abstract suspend fun state(): DeviceStateEntity?

    @Query("UPDATE device_state SET nextSequence = :nextSequence, bootId = :bootId WHERE id = 1")
    protected abstract suspend fun updateState(nextSequence: Long, bootId: String)

    @Transaction
    open suspend fun insertWithNextSequence(
        bootId: String,
        create: (Long) -> AttendanceEventEntity,
    ): AttendanceEventEntity {
        val current = state() ?: DeviceStateEntity(nextSequence = 1, bootId = bootId).also {
            insertState(it)
        }
        val event = create(current.nextSequence)
        insertEvent(event)
        updateState(current.nextSequence + 1, bootId)
        return event
    }

    @Query(
        "SELECT * FROM attendance_events WHERE syncState = 'PENDING' " +
            "ORDER BY deviceSequence ASC LIMIT :limit",
    )
    abstract suspend fun pending(limit: Int): List<AttendanceEventEntity>

    @Query("SELECT COUNT(*) FROM attendance_events WHERE syncState = 'PENDING'")
    abstract suspend fun pendingCount(): Int

    @Query(
        "UPDATE attendance_events SET syncAttempts = syncAttempts + 1, " +
            "lastSyncAttemptAtEpochMillis = :attemptedAt WHERE eventId IN (:eventIds)",
    )
    abstract suspend fun recordAttempt(eventIds: List<String>, attemptedAt: Long)

    @Query(
        "UPDATE attendance_events SET syncState = :state, acknowledgedAtEpochMillis = :acknowledgedAt, " +
            "rejectionReason = :reason WHERE eventId = :eventId AND syncState = 'PENDING'",
    )
    abstract suspend fun resolve(
        eventId: String,
        state: String,
        acknowledgedAt: Long,
        reason: String?,
    ): Int

    @Query("DELETE FROM attendance_events WHERE syncState = 'ACKNOWLEDGED' AND acknowledgedAtEpochMillis < :before")
    abstract suspend fun deleteAcknowledgedBefore(before: Long): Int
}
