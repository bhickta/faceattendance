package com.bhickta.faceattendance.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveLivenessChallengeTest {
    @Test
    fun requiresNeutralBlinkThenRandomTurn() {
        val challenge = ActiveLivenessChallenge(HeadTurnDirection.LEFT)

        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(1, -22f))
        assertEquals(ChallengeUpdate.RequestBlink, challenge.observe(1, 2f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 2f, 0.9f))
        assertEquals(ChallengeUpdate.WaitingForBlink, challenge.observe(1, 2f, 0.1f))
        assertEquals(ChallengeUpdate.RequestTurn(HeadTurnDirection.LEFT), challenge.observe(1, 2f, 0.9f))
        assertEquals(ChallengeUpdate.WaitingForTurn, challenge.observe(1, 12f, 0.9f))
        assertEquals(ChallengeUpdate.RequestNeutralAfterTurn, challenge.observe(1, -19f, 0.9f))
        assertEquals(ChallengeUpdate.WaitingForReturn, challenge.observe(1, -12f, 0.9f))
        assertEquals(ChallengeUpdate.Passed, challenge.observe(1, 1f, 0.9f))
    }

    @Test
    fun skipBlinkAdvancesToTurn() {
        val challenge = ActiveLivenessChallenge(HeadTurnDirection.RIGHT)

        assertEquals(ChallengeUpdate.RequestBlink, challenge.observe(1, 0f))
        challenge.skipBlink()
        assertEquals(ChallengeUpdate.WaitingForTurn, challenge.observe(1, 0f, 0.9f))
        assertEquals(ChallengeUpdate.RequestNeutralAfterTurn, challenge.observe(1, 20f, 0.9f))
        assertEquals(ChallengeUpdate.Passed, challenge.observe(1, 0f, 0.9f))
    }

    @Test
    fun rejectsMissingOrMultipleFaces() {
        val challenge = ActiveLivenessChallenge(HeadTurnDirection.RIGHT)

        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(0, null))
        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(2, 0f))
    }
}
