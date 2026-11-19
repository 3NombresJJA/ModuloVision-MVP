package com.google.mediapipe.examples.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs

/** Generic phase machine whose exercise-specific rules are supplied by a strategy. */
class ExerciseFSM(var currentStrategy: ExerciseStrategy = SquatStrategy()) {
    var currentCondition: ExerciseCondition = ExerciseCondition.IDLE
        private set
    var isConcentric: Boolean = false
        private set
    var activeSide: BodySide = BodySide.LEFT
        private set
    var viewOrientation: CameraViewOrientation = CameraViewOrientation.PROFILE
        private set
    @Volatile var enabled: Boolean = true
        private set

    /** Temporarily prevents pose frames from advancing the exercise state machine. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) currentCondition = ExerciseCondition.IDLE
    }

    private var lastY = Float.NaN
    private var minAngleInRep = Double.MAX_VALUE
    private var maxConcentricAngle = 0.0
    private var lockoutStartTime = 0L
    private var lastMovementTimeMs: Long = System.currentTimeMillis()
    private var noPoseStartTimeMs: Long = 0L

    /** Changes exercise and clears phase and motion history. */
    @Synchronized
    fun setStrategy(strategy: ExerciseStrategy) {
        currentStrategy = strategy
        currentCondition = ExerciseCondition.IDLE
        isConcentric = false
        lastY = Float.NaN
        minAngleInRep = Double.MAX_VALUE
        maxConcentricAngle = 0.0
        lockoutStartTime = 0L
        lastMovementTimeMs = System.currentTimeMillis()
        noPoseStartTimeMs = 0L
    }

    /** Minimum interval between inferences for the current exercise phase. */
    @Synchronized
    fun getTargetIntervalMs(): Long = when (currentCondition) {
        ExerciseCondition.IDLE -> 200L
        ExerciseCondition.ACTIVE -> 55L
        ExerciseCondition.PEAK -> 0L
        ExerciseCondition.LOCKOUT -> 90L
    }

    /** Extracts all required measurements through the currently selected strategy. */
    @Synchronized
    fun updateState(landmarks: List<NormalizedLandmark>, trainingActive: Boolean = false): ExerciseCondition {
        if (!enabled) return ExerciseCondition.IDLE
        noPoseStartTimeMs = 0L
        if (trainingActive && currentCondition == ExerciseCondition.IDLE) {
            val otherSide = activeSide.opposite
            if (otherSide.averageKeyVisibility(landmarks, currentStrategy.exerciseType) >=
                activeSide.averageKeyVisibility(landmarks, currentStrategy.exerciseType) + SIDE_VISIBILITY_HYSTERESIS) {
                activeSide = otherSide
                lastY = Float.NaN
            }
            viewOrientation = detectViewOrientation(landmarks)
        }
        val trackingY = currentStrategy.extractTrackingY(landmarks, activeSide)
        val velocityY = if (lastY.isNaN()) 0f else abs(trackingY - lastY)
        lastY = trackingY
        val now = System.currentTimeMillis()
        if (velocityY > MOVEMENT_NOISE_THRESHOLD) {
            lastMovementTimeMs = now
        }

        if (currentCondition != ExerciseCondition.IDLE &&
            now - lastMovementTimeMs >= INACTIVITY_TIMEOUT_MS
        ) {
            currentCondition = ExerciseCondition.IDLE
            isConcentric = false
            minAngleInRep = Double.MAX_VALUE
            maxConcentricAngle = 0.0
            lockoutStartTime = 0L
            lastY = Float.NaN
            lastMovementTimeMs = now
            return currentCondition
        }

        val primaryAngle = currentStrategy.extractPrimaryAngle(landmarks, activeSide)
        if (currentCondition == ExerciseCondition.ACTIVE) {
            if (isConcentric) maxConcentricAngle = maxOf(maxConcentricAngle, primaryAngle)
            else minAngleInRep = minOf(minAngleInRep, primaryAngle)
        }
        val previous = currentCondition
        val wasConcentric = isConcentric
        val transition = currentStrategy.evaluateCondition(
            previous, primaryAngle, velocityY, lockoutStartTime, isConcentric,
            minAngleInRep, maxConcentricAngle, viewOrientation
        )
        currentCondition = transition.condition
        isConcentric = transition.isConcentric
        if (previous == ExerciseCondition.IDLE && currentCondition == ExerciseCondition.ACTIVE) {
            minAngleInRep = primaryAngle
        }
        if (previous == ExerciseCondition.ACTIVE && currentCondition == ExerciseCondition.PEAK) {
            maxConcentricAngle = 0.0
        }
        if (previous == ExerciseCondition.PEAK && currentCondition == ExerciseCondition.ACTIVE) {
            maxConcentricAngle = primaryAngle
        }
        if (previous == ExerciseCondition.ACTIVE && wasConcentric && !transition.isConcentric &&
            currentCondition == ExerciseCondition.ACTIVE) {
            minAngleInRep = primaryAngle
        }
        if (currentCondition == ExerciseCondition.LOCKOUT || currentCondition == ExerciseCondition.IDLE) {
            minAngleInRep = Double.MAX_VALUE
            maxConcentricAngle = 0.0
        }
        if (previous != ExerciseCondition.LOCKOUT && currentCondition == ExerciseCondition.LOCKOUT) {
            lockoutStartTime = System.currentTimeMillis()
        }
        if (previous != ExerciseCondition.IDLE && currentCondition == ExerciseCondition.IDLE) {
            isConcentric = false
            lastMovementTimeMs = System.currentTimeMillis()
            lockoutStartTime = 0L
        }
        if (previous != ExerciseCondition.LOCKOUT && currentCondition == ExerciseCondition.LOCKOUT) {
            isConcentric = false
        }
        return currentCondition
    }

    /** Keeps the current phase briefly through transient pose detection drops. */
    @Synchronized
    fun onNoPoseDetected(): ExerciseCondition {
        if (!enabled) return ExerciseCondition.IDLE
        if (currentCondition == ExerciseCondition.IDLE) {
            noPoseStartTimeMs = 0L
            return ExerciseCondition.IDLE
        }

        val now = System.currentTimeMillis()
        if (noPoseStartTimeMs == 0L) {
            noPoseStartTimeMs = now
            return currentCondition
        }

        if (now - noPoseStartTimeMs >= NO_POSE_TIMEOUT_MS) {
            currentCondition = ExerciseCondition.IDLE
            isConcentric = false
            minAngleInRep = Double.MAX_VALUE
            maxConcentricAngle = 0.0
            lastY = Float.NaN
            lockoutStartTime = 0L
            lastMovementTimeMs = now
            noPoseStartTimeMs = 0L
        }
        return currentCondition
    }

    private fun detectViewOrientation(landmarks: List<NormalizedLandmark>): CameraViewOrientation {
        val shoulderWidth = kotlin.math.abs(landmarks[11].x() - landmarks[12].x())
        val torsoHeight = kotlin.math.abs(
            ((landmarks[11].y() + landmarks[12].y()) * 0.5f) -
                ((landmarks[23].y() + landmarks[24].y()) * 0.5f)
        )
        return if (torsoHeight > 0.01f && shoulderWidth / torsoHeight >= 0.35f) {
            CameraViewOrientation.FRONTAL
        } else CameraViewOrientation.PROFILE
    }

    private companion object {
        const val SIDE_VISIBILITY_HYSTERESIS = 0.10f
        const val MOVEMENT_NOISE_THRESHOLD = 0.005f
        const val INACTIVITY_TIMEOUT_MS = 3400L
        const val NO_POSE_TIMEOUT_MS = 1200L
    }
}
