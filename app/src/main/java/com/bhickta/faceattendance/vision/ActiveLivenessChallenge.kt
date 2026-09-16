package com.bhickta.faceattendance.vision

enum class HeadTurnDirection { LEFT, RIGHT }

sealed interface ChallengeUpdate {
    data object WaitingForNeutral : ChallengeUpdate
    data class RequestTurn(val direction: HeadTurnDirection) : ChallengeUpdate
    data object WaitingForTurn : ChallengeUpdate
    data object Passed : ChallengeUpdate
}

/** A neutral-to-random-turn challenge that prevents accepting a single static photograph. */
class ActiveLivenessChallenge(private val direction: HeadTurnDirection) {
    private var neutralObserved = false

    fun observe(faceCount: Int, yawDegrees: Float?): ChallengeUpdate {
        if (faceCount != 1 || yawDegrees == null) {
            return if (neutralObserved) ChallengeUpdate.WaitingForTurn else ChallengeUpdate.WaitingForNeutral
        }
        if (!neutralObserved) {
            if (kotlin.math.abs(yawDegrees) <= NEUTRAL_YAW) {
                neutralObserved = true
                return ChallengeUpdate.RequestTurn(direction)
            }
            return ChallengeUpdate.WaitingForNeutral
        }
        val passed = when (direction) {
            HeadTurnDirection.LEFT -> yawDegrees <= -TURN_YAW
            HeadTurnDirection.RIGHT -> yawDegrees >= TURN_YAW
        }
        return if (passed) ChallengeUpdate.Passed else ChallengeUpdate.WaitingForTurn
    }

    private companion object {
        const val NEUTRAL_YAW = 8f
        const val TURN_YAW = 18f
    }
}
