package com.bhickta.faceattendance.vision

enum class HeadTurnDirection { LEFT, RIGHT }

sealed interface ChallengeUpdate {
    data object WaitingForNeutral : ChallengeUpdate
    data class RequestTurn(val direction: HeadTurnDirection) : ChallengeUpdate
    data object WaitingForTurn : ChallengeUpdate
    data object RequestNeutralAfterTurn : ChallengeUpdate
    data object WaitingForReturn : ChallengeUpdate
    data object Passed : ChallengeUpdate
}

/** A neutral-to-random-turn challenge that prevents accepting a single static photograph. */
class ActiveLivenessChallenge(private val direction: HeadTurnDirection) {
    private var neutralObserved = false
    private var turnObserved = false

    fun observe(faceCount: Int, yawDegrees: Float?): ChallengeUpdate {
        if (faceCount != 1 || yawDegrees == null) {
            return when {
                turnObserved -> ChallengeUpdate.WaitingForReturn
                neutralObserved -> ChallengeUpdate.WaitingForTurn
                else -> ChallengeUpdate.WaitingForNeutral
            }
        }
        if (!neutralObserved) {
            if (kotlin.math.abs(yawDegrees) <= NEUTRAL_YAW) {
                neutralObserved = true
                return ChallengeUpdate.RequestTurn(direction)
            }
            return ChallengeUpdate.WaitingForNeutral
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
    }
}
