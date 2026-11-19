package com.google.mediapipe.examples.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

interface BiomechanicalEvaluator {
    fun evaluate(landmarks: List<NormalizedLandmark>, phase: ExerciseCondition, activeSide: BodySide, viewOrientation: CameraViewOrientation): FrameBiomechanicalEvaluation
}

abstract class BaseBiomechanicalEvaluator : BiomechanicalEvaluator {
    protected fun angle(points: List<NormalizedLandmark>, a: Int, b: Int, c: Int) =
        BaseExerciseStrategy.calculateAngle(points[a], points[b], points[c]).roundToInt()

    protected fun trunkInclination(points: List<NormalizedLandmark>, activeSide: BodySide): Int {
        val shoulder = points[activeSide.shoulder]
        val hip = points[activeSide.hip]
        return Math.toDegrees(atan2(abs(shoulder.x() - hip.x()).toDouble(), abs(shoulder.y() - hip.y()).toDouble())).roundToInt()
    }

    protected fun error(code: String, phase: ExerciseCondition, milestone: Boolean, depth: Boolean? = null) =
        RawDetectedError(code, phase.label(), depth, milestone)

    protected fun result(angles: Map<String, Int>, errors: List<RawDetectedError>) =
        FrameBiomechanicalEvaluation(angles, errors)

    protected fun ExerciseCondition.label() = when (this) {
        ExerciseCondition.ACTIVE -> "en_ejecucion"
        ExerciseCondition.PEAK -> "maxima_profundidad"
        ExerciseCondition.LOCKOUT -> "bloqueo_final"
        ExerciseCondition.IDLE -> "reposo"
    }
}

class SquatEvaluator : BaseBiomechanicalEvaluator() {
    override fun evaluate(landmarks: List<NormalizedLandmark>, phase: ExerciseCondition, activeSide: BodySide, viewOrientation: CameraViewOrientation): FrameBiomechanicalEvaluation {
        val knee = angle(landmarks, activeSide.hip, activeSide.knee, activeSide.ankle)
        val back = angle(landmarks, activeSide.shoulder, activeSide.hip, activeSide.knee)
        val errors = mutableListOf<RawDetectedError>()
        if ((phase == ExerciseCondition.ACTIVE || phase == ExerciseCondition.PEAK) && back < 45)
            errors += error("ERR_INCLINACION_TRONCO", phase, false)
        if (phase == ExerciseCondition.PEAK) when {
            (viewOrientation == CameraViewOrientation.PROFILE && knee > 100) ||
                (viewOrientation == CameraViewOrientation.FRONTAL && knee > 118) -> errors += error("ERR_POCA_PROFUNDIDAD", phase, true, false)
            knee < 60 -> errors += error("ERR_EXCESO_PROFUNDIDAD", phase, true, true)
        }
        return result(linkedMapOf("rodilla" to knee, "espalda" to back), errors)
    }
}

class DeadliftEvaluator : BaseBiomechanicalEvaluator() {
    override fun evaluate(landmarks: List<NormalizedLandmark>, phase: ExerciseCondition, activeSide: BodySide, viewOrientation: CameraViewOrientation): FrameBiomechanicalEvaluation {
        val hip = angle(landmarks, activeSide.shoulder, activeSide.hip, activeSide.knee)
        val back = trunkInclination(landmarks, activeSide)
        val errors = mutableListOf<RawDetectedError>()
        if ((phase == ExerciseCondition.ACTIVE || phase == ExerciseCondition.PEAK) && hip < 70)
            errors += error("ERR_CURVATURA_ESPALDA", phase, false)
        if (phase == ExerciseCondition.LOCKOUT && hip < 165)
            errors += error("ERR_BLOQUEO_INCOMPLETO", phase, true)
        return result(linkedMapOf("cadera" to hip, "espalda" to back), errors)
    }
}

class BicepCurlEvaluator : BaseBiomechanicalEvaluator() {
    override fun evaluate(landmarks: List<NormalizedLandmark>, phase: ExerciseCondition, activeSide: BodySide, viewOrientation: CameraViewOrientation): FrameBiomechanicalEvaluation {
        val elbow = angle(landmarks, activeSide.shoulder, activeSide.elbow, activeSide.wrist)
        val trunk = trunkInclination(landmarks, activeSide)
        val errors = mutableListOf<RawDetectedError>()
        if ((phase == ExerciseCondition.ACTIVE || phase == ExerciseCondition.PEAK) && trunk > 15)
            errors += error("ERR_BALANCEO_CUERPO", phase, false)
        if (phase == ExerciseCondition.PEAK && elbow > 50)
            errors += error("ERR_RANGO_INCOMPLETO", phase, true)
        return result(linkedMapOf("codo" to elbow, "tronco" to trunk), errors)
    }
}

class BiomechanicalEngine(private var evaluator: BiomechanicalEvaluator = SquatEvaluator()) {
    fun setExerciseType(type: ExerciseType) { evaluator = when (type) {
        ExerciseType.SQUAT -> SquatEvaluator()
        ExerciseType.DEADLIFT -> DeadliftEvaluator()
        ExerciseType.BICEP_CURL -> BicepCurlEvaluator()
    } }

    fun analyzeFrame(landmarks: List<NormalizedLandmark>, phase: ExerciseCondition, activeSide: BodySide, viewOrientation: CameraViewOrientation) =
        evaluator.evaluate(landmarks, phase, activeSide, viewOrientation)
}
