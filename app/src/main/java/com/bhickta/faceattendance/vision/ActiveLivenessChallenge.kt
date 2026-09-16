package com.bhickta.faceattendance.vision

import kotlin.math.max
import kotlin.math.min

sealed interface ChallengeUpdate {
    data object WaitingForNeutral : ChallengeUpdate
    data object RequestBlink : ChallengeUpdate
    data object WaitingForBlink : ChallengeUpdate
    data object Passed : ChallengeUpdate
}

/**
 * A neutral-then-blink challenge that rejects a single static photograph.
 *
 * Eye-open probabilities are relative rather than fixed so the challenge still detects a blink
 * through spectacles, where the model often reports a lower open value. [skipBlink] lets the
 * caller proceed when the eye signal is too weak or unavailable.
 */
class ActiveLivenessChallenge {
    private var neutralObserved = false
    private var blinkRequested = false
    private var eyesClosedSeen = false
    private var blinkObserved = false
    private var baselineOpen = 0f

    fun skipBlink() {
        if (neutralObserved && !blinkObserved) {
            blinkObserved = true
        }
    }

    fun observe(
        faceCount: Int,
        yawDegrees: Float?,
        eyesOpenProbability: Float? = null,
    ): ChallengeUpdate {
        if (faceCount != 1 || yawDegrees == null) {
            return when {
                blinkObserved -> ChallengeUpdate.Passed
                blinkRequested -> ChallengeUpdate.WaitingForBlink
                neutralObserved -> ChallengeUpdate.RequestBlink
                else -> ChallengeUpdate.WaitingForNeutral
            }
        }
        if (!neutralObserved) {
            if (kotlin.math.abs(yawDegrees) <= NEUTRAL_YAW) {
                neutralObserved = true
                return ChallengeUpdate.RequestBlink
            }
            return ChallengeUpdate.WaitingForNeutral
        }
        if (!blinkObserved) {
            blinkRequested = true
            if (eyesOpenProbability != null) {
                baselineOpen = max(baselineOpen, eyesOpenProbability)
                val closedThreshold = min(MAXIMUM_CLOSED, baselineOpen * CLOSED_FRACTION)
                val openThreshold = max(MINIMUM_OPEN, baselineOpen * OPEN_FRACTION)
                if (eyesOpenProbability <= closedThreshold) {
                    eyesClosedSeen = true
                } else if (eyesClosedSeen && eyesOpenProbability >= openThreshold) {
                    blinkObserved = true
                    return ChallengeUpdate.Passed
                }
            }
            return ChallengeUpdate.WaitingForBlink
        }
        return ChallengeUpdate.Passed
    }

    private companion object {
        const val NEUTRAL_YAW = 8f
        const val MAXIMUM_CLOSED = 0.35f
        const val CLOSED_FRACTION = 0.55f
        const val MINIMUM_OPEN = 0.35f
        const val OPEN_FRACTION = 0.8f
    }
}
