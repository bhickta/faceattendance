package com.bhickta.faceattendance.vision

enum class HeadTurnDirection { LEFT, RIGHT }

sealed interface ChallengeUpdate {
    data object WaitingForNeutral : ChallengeUpdate
    data object RequestBlink : ChallengeUpdate
    data object WaitingForBlink : ChallengeUpdate
    data class RequestTurn(val direction: HeadTurnDirection) : ChallengeUpdate
    data object WaitingForTurn : ChallengeUpdate
    data object RequestNeutralAfterTurn : ChallengeUpdate
    data object WaitingForReturn : ChallengeUpdate
    data object Passed : ChallengeUpdate
}

/**
 * A neutral-to-blink-to-random-turn challenge that prevents accepting a single static photograph.
 * The blink step can be skipped with [skipBlink] when eye-open classification is unavailable.
 */
class ActiveLivenessChallenge(private val direction: HeadTurnDirection) {
    private var neutralObserved = false
    private var blinkRequested = false
    private var eyesClosedSeen = false
    private var blinkObserved = false
    private var turnObserved = false

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
                turnObserved -> ChallengeUpdate.WaitingForReturn
                blinkObserved -> ChallengeUpdate.WaitingForTurn
                neutralObserved -> if (blinkRequested) ChallengeUpdate.WaitingForBlink else ChallengeUpdate.RequestBlink
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
                    return ChallengeUpdate.RequestTurn(direction)
                }
            }
            return ChallengeUpdate.WaitingForBlink
        }
        if (!turnObserved) {
            val turned = when (direction) {
                HeadTurnDirection.LEFT -> yawDegrees <= -TURN_YAW
                HeadTurnDirection.RIGHT -> yawDegrees >= TURN_YAW
            }
            if (turned) {
                turnObserved = true
                return ChallengeUpdate.RequestNeutralAfterTurn
            }
            return ChallengeUpdate.WaitingForTurn
        }
        return if (kotlin.math.abs(yawDegrees) <= NEUTRAL_YAW) {
            ChallengeUpdate.Passed
        } else {
            ChallengeUpdate.WaitingForReturn
        }
    }

    private companion object {
        const val NEUTRAL_YAW = 8f
        const val TURN_YAW = 18f
        const val EYES_CLOSED = 0.3f
        const val EYES_OPEN = 0.6f
    }
}
