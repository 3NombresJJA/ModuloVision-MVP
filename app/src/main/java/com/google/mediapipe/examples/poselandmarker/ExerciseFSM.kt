package com.google.mediapipe.examples.poselandmarker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// Exercise phases used to adapt the live pose inference rate.
enum class ExerciseCondition {
    IDLE,
    ACTIVE,
    PEAK,
    LOCKOUT
}

class ExerciseFSM {

    var currentCondition: ExerciseCondition = ExerciseCondition.IDLE
        private set

    private var lastHipY: Float = 0f
    private var lockoutStartTime: Long = 0L

    /** Minimum interval between inferences for the current exercise phase. */
    fun getTargetIntervalMs(): Long = when (currentCondition) {
        ExerciseCondition.IDLE -> 200L
        ExerciseCondition.ACTIVE -> 55L
        ExerciseCondition.PEAK -> 0L
        ExerciseCondition.LOCKOUT -> 90L
    }

    /** Update the exercise phase from normalized hip position and knee angle. */
    fun updateState(hipY: Float, kneeAngle: Double): ExerciseCondition {
        val velocityY = abs(hipY - lastHipY)
        lastHipY = hipY
        val isMoving = velocityY > 0.012f

        currentCondition = when (currentCondition) {
            ExerciseCondition.IDLE -> {
                if (isMoving) ExerciseCondition.ACTIVE else ExerciseCondition.IDLE
            }
            ExerciseCondition.ACTIVE -> {
                if (velocityY < 0.004f && kneeAngle < 105.0) {
                    ExerciseCondition.PEAK
                } else {
                    ExerciseCondition.ACTIVE
                }
            }
            ExerciseCondition.PEAK -> {
                if (velocityY > 0.008f) {
                    if (kneeAngle > 160.0) {
                        lockoutStartTime = System.currentTimeMillis()
                        ExerciseCondition.LOCKOUT
                    } else {
                        ExerciseCondition.ACTIVE
                    }
                } else {
                    ExerciseCondition.PEAK
                }
            }
            ExerciseCondition.LOCKOUT -> {
                val timeInLockout = System.currentTimeMillis() - lockoutStartTime
                if (isMoving && timeInLockout > 400L) {
                    ExerciseCondition.ACTIVE
                } else if (timeInLockout > 3000L) {
                    ExerciseCondition.IDLE
                } else {
                    ExerciseCondition.LOCKOUT
                }
            }
        }
        return currentCondition
    }

    companion object {
        /** Angle at the knee, in degrees, given hip, knee, and ankle points. */
        fun calculateKneeAngle(
            hipX: Float, hipY: Float,
            kneeX: Float, kneeY: Float,
            ankleX: Float, ankleY: Float
        ): Double {
            val firstX = hipX - kneeX
            val firstY = hipY - kneeY
            val secondX = ankleX - kneeX
            val secondY = ankleY - kneeY
            val dot = firstX * secondX + firstY * secondY
            val firstLength = sqrt(firstX * firstX + firstY * firstY)
            val secondLength = sqrt(secondX * secondX + secondY * secondY)
            if (firstLength == 0f || secondLength == 0f) return 180.0

            val cosine = (dot / (firstLength * secondLength)).coerceIn(-1f, 1f)
            return Math.toDegrees(atan2(sqrt(1f - cosine * cosine), cosine).toDouble())
        }
    }
}
