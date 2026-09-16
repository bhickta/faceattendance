package com.bhickta.faceattendance.device

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import com.bhickta.faceattendance.attendance.TimestampConfidence
import kotlin.math.abs

object ClockTrust {
    private const val MAX_WALL_CLOCK_DRIFT_MILLIS = 5 * 60 * 1000L

    fun expectedServerTime(
        configuration: DeviceConfiguration,
        elapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): Long? {
        val elapsed = elapsedRealtime - configuration.elapsedAtServerTimeMillis
        if (elapsed < 0) return null
        return configuration.serverTimeEpochMillis + elapsed
    }

    fun hasValidAuthorization(
        configuration: DeviceConfiguration,
        elapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): Boolean = expectedServerTime(configuration, elapsedRealtime)
        ?.let { it <= configuration.authorizationExpiresAtEpochMillis } == true

    fun timestampConfidence(
        contentResolver: ContentResolver,
        configuration: DeviceConfiguration,
        wallClockMillis: Long = System.currentTimeMillis(),
    ): TimestampConfidence {
        val expected = expectedServerTime(configuration) ?: return TimestampConfidence.UNVERIFIED
        val automaticTime = Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 0) == 1
        return if (automaticTime && abs(wallClockMillis - expected) <= MAX_WALL_CLOCK_DRIFT_MILLIS) {
            TimestampConfidence.TRUSTED
        } else {
            TimestampConfidence.CLOCK_CHANGED
        }
    }
}
