package com.bhickta.faceattendance.vision

sealed interface ChallengeUpdate {
    data object WaitingForNeutral : ChallengeUpdate
    data object RequestBlink : ChallengeUpdate
    data object WaitingForBlink : ChallengeUpdate
    data object Passed : ChallengeUpdate
}

/**
 * A neutral-then-blink challenge that rejects a single static photograph.
 * The blink step can be skipped with [skipBlink] when eye-open classification is unavailable.
 */
class ActiveLivenessChallenge {
    private var neutralObserved = false
    private var blinkRequested = false
    private var eyesClosedSeen = false
    private var blinkObserved = false

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
                if (eyesOpenProbability <= EYES_CLOSED) {
                    eyesClosedSeen = true
                } else if (eyesClosedSeen && eyesOpenProbability >= EYES_OPEN) {
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
        const val EYES_CLOSED = 0.3f
        const val EYES_OPEN = 0.6f
    }
}
