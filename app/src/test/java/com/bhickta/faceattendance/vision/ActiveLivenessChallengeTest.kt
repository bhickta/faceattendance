package com.bhickta.faceattendance.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveLivenessChallengeTest {
    @Test
    fun requiresNeutralBeforeRandomTurn() {
        val challenge = ActiveLivenessChallenge(HeadTurnDirection.LEFT)

        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(1, -22f))
        assertEquals(
            ChallengeUpdate.RequestTurn(HeadTurnDirection.LEFT),
            challenge.observe(1, 2f),
        )
        assertEquals(ChallengeUpdate.WaitingForTurn, challenge.observe(1, 12f))
        assertEquals(ChallengeUpdate.RequestNeutralAfterTurn, challenge.observe(1, -19f))
        assertEquals(ChallengeUpdate.WaitingForReturn, challenge.observe(1, -12f))
        assertEquals(ChallengeUpdate.Passed, challenge.observe(1, 1f))
    }

    @Test
    fun rejectsMissingOrMultipleFaces() {
        val challenge = ActiveLivenessChallenge(HeadTurnDirection.RIGHT)

        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(0, null))
        assertEquals(ChallengeUpdate.WaitingForNeutral, challenge.observe(2, 0f))
    }
}
