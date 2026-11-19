package com.google.mediapipe.examples.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.atan2
import kotlin.math.sqrt

/** Phases shared by the FSM and all exercise strategies. */
enum class ExerciseCondition {
    IDLE,
    ACTIVE,
    PEAK,
    LOCKOUT
}

enum class CameraViewOrientation { PROFILE, FRONTAL }

/** User-selectable exercise with its English UI label. */
enum class ExerciseType(val displayName: String) {
    SQUAT("Squat"),
    DEADLIFT("Deadlift"),
    BICEP_CURL("Bicep Curl")
}

/** Side-specific MediaPipe Pose landmark indices. */
enum class BodySide(
    val shoulder: Int,
    val elbow: Int,
    val wrist: Int,
    val hip: Int,
    val knee: Int,
    val ankle: Int
) {
    LEFT(11, 13, 15, 23, 25, 27),
    RIGHT(12, 14, 16, 24, 26, 28);

    val opposite: BodySide get() = if (this == LEFT) RIGHT else LEFT

    fun averageKeyVisibility(landmarks: List<NormalizedLandmark>, exerciseType: ExerciseType): Float = when (exerciseType) {
        ExerciseType.BICEP_CURL -> (landmarks[shoulder].visibility().orElse(0f) +
            landmarks[elbow].visibility().orElse(0f) + landmarks[wrist].visibility().orElse(0f) +
            landmarks[hip].visibility().orElse(0f)) * 0.25f
        ExerciseType.SQUAT, ExerciseType.DEADLIFT -> (landmarks[shoulder].visibility().orElse(0f) +
            landmarks[hip].visibility().orElse(0f) + landmarks[knee].visibility().orElse(0f) +
            landmarks[ankle].visibility().orElse(0f)) * 0.25f
    }
}

/** Defines landmark selection and phase rules for a single exercise. */
interface ExerciseStrategy {
    val exerciseType: ExerciseType
    fun extractPrimaryAngle(landmarks: List<NormalizedLandmark>, activeSide: BodySide): Double
    fun extractTrackingY(landmarks: List<NormalizedLandmark>, activeSide: BodySide): Float
    fun evaluateCondition(
        currentCondition: ExerciseCondition,
        primaryAngle: Double,
        velocityY: Float,
        lockoutStartTime: Long,
        isConcentric: Boolean,
        minAngleInRep: Double,
        maxConcentricAngle: Double,
        viewOrientation: CameraViewOrientation
    ): ExerciseTransition
}

/** State and movement direction produced by one strategy evaluation. */
data class ExerciseTransition(val condition: ExerciseCondition, val isConcentric: Boolean)

/** Shared MediaPipe Pose landmark indexing, angle calculation, and phase timing. */
abstract class BaseExerciseStrategy : ExerciseStrategy {
    protected fun angle(landmarks: List<NormalizedLandmark>, first: Int, middle: Int, last: Int) =
        calculateAngle(landmarks[first], landmarks[middle], landmarks[last])

    override fun extractTrackingY(landmarks: List<NormalizedLandmark>, activeSide: BodySide): Float = landmarks[activeSide.hip].y()

    protected fun transition(
        current: ExerciseCondition,
        angle: Double,
        velocityY: Float,
        lockoutStartTime: Long,
        startThreshold: Float,
        peakAngle: Double,
        lockoutAngle: Double,
        isConcentric: Boolean,
        minAngleInRep: Double,
        maxConcentricAngle: Double,
        effectivePeakAngle: Double = peakAngle
    ): ExerciseTransition = when (current) {
        ExerciseCondition.IDLE -> if (velocityY > startThreshold) {
            ExerciseTransition(ExerciseCondition.ACTIVE, false)
        } else ExerciseTransition(current, false)
        ExerciseCondition.ACTIVE -> if (!isConcentric && angle < effectivePeakAngle &&
            (velocityY < 0.005f || (minAngleInRep < effectivePeakAngle && angle - minAngleInRep >= 3.5))) {
            ExerciseTransition(ExerciseCondition.PEAK, false)
        } else if (isConcentric && angle > lockoutAngle) {
            ExerciseTransition(ExerciseCondition.LOCKOUT, false)
        } else if (isConcentric && maxConcentricAngle >= lockoutAngle - 15.0 &&
            maxConcentricAngle - angle >= 6.0) {
            ExerciseTransition(ExerciseCondition.ACTIVE, false)
        } else ExerciseTransition(current, isConcentric)
        ExerciseCondition.PEAK -> if (angle > effectivePeakAngle + 5.0) {
            ExerciseTransition(ExerciseCondition.ACTIVE, true)
        } else ExerciseTransition(current, false)
        ExerciseCondition.LOCKOUT -> {
            val elapsed = System.currentTimeMillis() - lockoutStartTime
            if (elapsed > 3000L) ExerciseTransition(ExerciseCondition.IDLE, false)
            else if (elapsed > 400L && velocityY > 0.012f) {
                ExerciseTransition(ExerciseCondition.ACTIVE, false)
            } else ExerciseTransition(current, false)
        }
    }

    companion object {
        /** Returns the interior 2D angle in degrees; zero-length vectors yield 180°. */
        fun calculateAngle(p1: NormalizedLandmark, p2: NormalizedLandmark, p3: NormalizedLandmark): Double {
            val ax = p1.x() - p2.x()
            val ay = p1.y() - p2.y()
            val bx = p3.x() - p2.x()
            val by = p3.y() - p2.y()
            val aLength = sqrt(ax * ax + ay * ay)
            val bLength = sqrt(bx * bx + by * by)
            if (aLength == 0f || bLength == 0f) return 180.0
            val cosine = ((ax * bx + ay * by) / (aLength * bLength)).coerceIn(-1f, 1f)
            return Math.toDegrees(atan2(sqrt(1f - cosine * cosine).toDouble(), cosine.toDouble()))
        }
    }
}

class SquatStrategy : BaseExerciseStrategy() {
    override val exerciseType = ExerciseType.SQUAT
    override fun extractPrimaryAngle(landmarks: List<NormalizedLandmark>, activeSide: BodySide) = angle(landmarks, activeSide.hip, activeSide.knee, activeSide.ankle)
    override fun evaluateCondition(currentCondition: ExerciseCondition, primaryAngle: Double, velocityY: Float, lockoutStartTime: Long, isConcentric: Boolean, minAngleInRep: Double, maxConcentricAngle: Double, viewOrientation: CameraViewOrientation) =
        transition(currentCondition, primaryAngle, velocityY, lockoutStartTime, 0.010f, 110.0, 160.0, isConcentric, minAngleInRep, maxConcentricAngle,
            if (viewOrientation == CameraViewOrientation.FRONTAL) 125.0 else 110.0)
}

class DeadliftStrategy : BaseExerciseStrategy() {
    override val exerciseType = ExerciseType.DEADLIFT
    override fun extractPrimaryAngle(landmarks: List<NormalizedLandmark>, activeSide: BodySide) = angle(landmarks, activeSide.shoulder, activeSide.hip, activeSide.knee)
    override fun evaluateCondition(currentCondition: ExerciseCondition, primaryAngle: Double, velocityY: Float, lockoutStartTime: Long, isConcentric: Boolean, minAngleInRep: Double, maxConcentricAngle: Double, viewOrientation: CameraViewOrientation) =
        transition(currentCondition, primaryAngle, velocityY, lockoutStartTime, 0.010f, 110.0, 165.0, isConcentric, minAngleInRep, maxConcentricAngle)
}

class BicepCurlStrategy : BaseExerciseStrategy() {
    override val exerciseType = ExerciseType.BICEP_CURL
    override fun extractPrimaryAngle(landmarks: List<NormalizedLandmark>, activeSide: BodySide) = angle(landmarks, activeSide.shoulder, activeSide.elbow, activeSide.wrist)
    override fun extractTrackingY(landmarks: List<NormalizedLandmark>, activeSide: BodySide) = landmarks[activeSide.wrist].y()
    override fun evaluateCondition(currentCondition: ExerciseCondition, primaryAngle: Double, velocityY: Float, lockoutStartTime: Long, isConcentric: Boolean, minAngleInRep: Double, maxConcentricAngle: Double, viewOrientation: CameraViewOrientation) =
        transition(currentCondition, primaryAngle, velocityY, lockoutStartTime, 0.008f, 70.0, 150.0, isConcentric, minAngleInRep, maxConcentricAngle)
}
