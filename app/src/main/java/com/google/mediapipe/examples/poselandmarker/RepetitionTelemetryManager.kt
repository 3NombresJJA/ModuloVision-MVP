package com.google.mediapipe.examples.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/** Collects frame diagnostics and emits one consolidated JSON document per training set. */
class RepetitionTelemetryManager(
    private val fsm: ExerciseFSM,
    private val engine: BiomechanicalEngine = BiomechanicalEngine(),
    private val formatter: ContextJsonFormatter = ContextJsonFormatter()
) {
    private val trajectoryBuffer = AngleTrajectoryBuffer(11)
    private val reportedErrorCodesInRep = mutableSetOf<String>()
    private val postureDebounceCounters = mutableMapOf<String, Int>()
    private val accumulatedSnapshots = mutableListOf<ErrorSnapshot>()
    var isTrainingActive: Boolean = false
        private set
    private var completedReps: Int = 0
    private var currentRepetition: Int = 1
    private var hasReachedPeakInCurrentRep: Boolean = false
    val repetitionCount: Int
        get() = completedReps
    private var exerciseType = fsm.currentStrategy.exerciseType

    @Synchronized fun setExercise(type: ExerciseType, strategy: ExerciseStrategy) {
        fsm.setStrategy(strategy)
        engine.setExerciseType(type)
        exerciseType = type
        isTrainingActive = false
        resetSetRepetitionState()
        accumulatedSnapshots.clear()
        resetRepetitionTracking()
    }

    @Synchronized fun startTrainingSet() {
        resetSetRepetitionState()
        accumulatedSnapshots.clear()
        resetRepetitionTracking()
        fsm.setStrategy(fsm.currentStrategy)
        isTrainingActive = true
    }

    @Synchronized fun endTrainingSet(userProfile: UserProfile? = null): String? {
        if (!isTrainingActive) return null
        isTrainingActive = false
        val json = formatter.format(ContextPayload(
            Metadatos(exerciseType.displayName, completedReps, userProfile),
            DiagnosticoBiomecanico(accumulatedSnapshots.toList())
        ))
        accumulatedSnapshots.clear()
        resetSetRepetitionState()
        resetRepetitionTracking()
        fsm.setStrategy(fsm.currentStrategy)
        return json
    }

    /** Updates the shared FSM exactly once and analyzes the same side selected for its kinematics. */
    @Synchronized fun processFrame(landmarks: List<NormalizedLandmark>): String? {
        if (!isTrainingActive) return null
        val previousPhase = fsm.currentCondition
        val previousIsConcentric = fsm.isConcentric
        val currentPhase = fsm.updateState(landmarks, trainingActive = true)
        if (previousPhase != ExerciseCondition.PEAK && currentPhase == ExerciseCondition.PEAK) {
            if (!hasReachedPeakInCurrentRep) {
                completedReps++
                hasReachedPeakInCurrentRep = true
            }
        }
        val startsNewRep =
            (previousPhase == ExerciseCondition.LOCKOUT || previousPhase == ExerciseCondition.IDLE) &&
                currentPhase == ExerciseCondition.ACTIVE ||
                previousPhase == ExerciseCondition.ACTIVE && currentPhase == ExerciseCondition.ACTIVE &&
                previousIsConcentric && !fsm.isConcentric
        if (startsNewRep) {
            currentRepetition = completedReps + 1
            hasReachedPeakInCurrentRep = false
            resetRepetitionTracking()
        }
        val abandoned = currentPhase == ExerciseCondition.IDLE && previousPhase != ExerciseCondition.IDLE
        if (abandoned) {
            resetRepetitionTracking()
            hasReachedPeakInCurrentRep = false
        }

        if (currentPhase != ExerciseCondition.IDLE) {
            val evaluation = engine.analyzeFrame(landmarks, currentPhase, fsm.activeSide, fsm.viewOrientation)
            trajectoryBuffer.addFrame(evaluation.angulosActuales)
            val presentPostureCodes = evaluation.erroresEnFrame.asSequence()
                .filter { !it.esErrorDeHito }.map { it.codigo }.toSet()
            postureDebounceCounters.keys.retainAll(presentPostureCodes)
            for (rawError in evaluation.erroresEnFrame) {
                if (rawError.codigo in reportedErrorCodesInRep) continue
                if (!rawError.esErrorDeHito) {
                    val count = (postureDebounceCounters[rawError.codigo] ?: 0) + 1
                    postureDebounceCounters[rawError.codigo] = count
                    if (count < 3) continue
                }
                reportedErrorCodesInRep += rawError.codigo
                accumulatedSnapshots += ErrorSnapshot(
                    repeticion = currentRepetition,
                    codigo = rawError.codigo,
                    fase_donde_ocurrio = rawError.faseDondeOcurrio,
                    cumple_profundidad = rawError.cumpleProfundidad,
                    angulos_en_el_fallo = evaluation.angulosActuales.toMap(),
                    trayectoria_reciente = trajectoryBuffer.extractSubsampledTrajectory()
                )
            }
        }

        return null
    }

    /** Advances the missing-pose timeout and closes or discards a repetition if it expires. */
    @Synchronized fun onNoPoseDetected(): String? {
        if (!isTrainingActive) return null
        val previousPhase = fsm.currentCondition
        val currentPhase = fsm.onNoPoseDetected()
        if (previousPhase != currentPhase && currentPhase == ExerciseCondition.IDLE) resetRepetitionTracking()
        return null
    }

    private fun resetRepetitionTracking() {
        trajectoryBuffer.clear()
        reportedErrorCodesInRep.clear()
        postureDebounceCounters.clear()
    }

    private fun resetSetRepetitionState() {
        completedReps = 0
        currentRepetition = 1
        hasReachedPeakInCurrentRep = false
    }
}
