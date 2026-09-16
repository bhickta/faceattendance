package com.bhickta.faceattendance.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTrustTest {
    private val configuration = DeviceConfiguration(
        baseUrl = "https://attendance.example.com",
        apiKey = "key",
        apiSecret = "secret",
        deviceId = "KIOSK-1",
        branchId = "BRANCH-1",
        gateId = "GATE-1",
        directionMode = "SELECT",
        assignmentVersion = "1",
        serverTimeEpochMillis = 1_000_000,
        elapsedAtServerTimeMillis = 25_000,
        authorizationExpiresAtEpochMillis = 1_100_000,
    )

    @Test
    fun advancesTrustedTimeUsingMonotonicClock() {
        assertEquals(1_075_000L, ClockTrust.expectedServerTime(configuration, 100_000))
    }

    @Test
    fun rejectsAnchorAfterDeviceReboot() {
        assertNull(ClockTrust.expectedServerTime(configuration, 10_000))
        assertFalse(ClockTrust.hasValidAuthorization(configuration, 10_000))
    }

    @Test
    fun expiresAuthorizationAtLeaseBoundary() {
        assertTrue(ClockTrust.hasValidAuthorization(configuration, 125_000))
        assertFalse(ClockTrust.hasValidAuthorization(configuration, 125_001))
    }
}
