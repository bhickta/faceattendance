package com.bhickta.faceattendance.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveLivenessChallengeTest {
    @Test
    fun requiresNeutralThenBlink() {
        val challenge = ActiveLivenessChallenge()

        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(1, -22f))
        assertEquals(ChallengeUpdate.RequestBlink, challenge.observe(1, 2f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 2f, 0.9f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 2f, 0.1f))
        assertEquals(ChallengeUpdate.Passed, challenge.observe(1, 2f, 0.9f))
    }

    @Test
    fun detectsBlinkWithLowerBaselineForGlasses() {
        val challenge = ActiveLivenessChallenge()

        assertEquals(ChallengeUpdate.RequestBlink, challenge.observe(1, 0f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 0f, 0.6f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 0f, 0.25f))
        assertEquals(ChallengeUpdate.Passed, challenge.observe(1, 0f, 0.6f))
    }

    @Test
    fun staysWaitingWhenEyeSignalTooWeak() {
        val challenge = ActiveLivenessChallenge()

        assertEquals(ChallengeUpdate.RequestBlink, challenge.observe(1, 0f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 0f, 0.3f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 0f, 0.1f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 0f, 0.3f))
    }

    @Test
    fun skipBlinkPassesWithoutEyeClassification() {
        val challenge = ActiveLivenessChallenge()

        assertEquals(ChallengeUpdate.RequestBlink, challenge.observe(1, 0f))
        challenge.skipBlink()
        assertEquals(ChallengeUpdate.Passed, challenge.observe(1, 0f, 0.9f))
    }

    @Test
    fun rejectsMissingOrMultipleFaces() {
        val challenge = ActiveLivenessChallenge()

        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(0, null))
        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(2, 0f))
    }
}
