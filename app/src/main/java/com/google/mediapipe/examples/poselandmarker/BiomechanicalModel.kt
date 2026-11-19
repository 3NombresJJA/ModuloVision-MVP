package com.google.mediapipe.examples.poselandmarker

data class RawDetectedError(
    val codigo: String,
    val faseDondeOcurrio: String,
    val cumpleProfundidad: Boolean? = null,
    val esErrorDeHito: Boolean
)

data class FrameBiomechanicalEvaluation(
    val angulosActuales: Map<String, Int>,
    val erroresEnFrame: List<RawDetectedError>
)

data class ErrorSnapshot(
    val repeticion: Int,
    val codigo: String,
    val fase_donde_ocurrio: String,
    val cumple_profundidad: Boolean? = null,
    val angulos_en_el_fallo: Map<String, Int>,
    val trayectoria_reciente: Map<String, List<Int>>
)

data class Metadatos(
    val ejercicio: String,
    val repeticiones: Int,
    val perfil_usuario: UserProfile? = null
)

data class DiagnosticoBiomecanico(val errores_detectados_en_repeticion: List<ErrorSnapshot>)

data class ContextPayload(
    val metadatos: Metadatos,
    val diagnostico_biomecanico: DiagnosticoBiomecanico
)
