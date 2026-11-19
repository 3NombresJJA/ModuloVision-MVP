package com.google.mediapipe.examples.poselandmarker

import org.json.JSONArray
import org.json.JSONObject

/** Serializes only the completed repetition payload, keeping JSON work off active frames. */
class ContextJsonFormatter {
    fun format(payload: ContextPayload): String {
        val errors = JSONArray()
        payload.diagnostico_biomecanico.errores_detectados_en_repeticion.forEach { snapshot ->
            val angles = JSONObject()
            snapshot.angulos_en_el_fallo.forEach { (joint, value) -> angles.put(joint, value) }
            val trajectory = JSONObject()
            snapshot.trayectoria_reciente.forEach { (joint, values) ->
                trajectory.put(joint, JSONArray(values))
            }
            val item = JSONObject()
                .put("repeticion", snapshot.repeticion)
                .put("codigo", snapshot.codigo)
                .put("fase_donde_ocurrio", snapshot.fase_donde_ocurrio)
            if (snapshot.cumple_profundidad != null) item.put("cumple_profundidad", snapshot.cumple_profundidad)
            item.put("angulos_en_el_fallo", angles).put("trayectoria_reciente", trajectory)
            errors.put(item)
        }
        val metadatos = JSONObject()
            .put("ejercicio", payload.metadatos.ejercicio)
            .put("repeticiones", payload.metadatos.repeticiones)
        payload.metadatos.perfil_usuario?.let { profile ->
            metadatos.put("perfil_usuario", JSONObject()
                .put("edad", profile.edad)
                .put("altura_cm", profile.alturaCm)
                .put("peso_kg", Math.round(profile.pesoKg * 10.0) / 10.0)
                .put("genero", profile.genero))
        }
        return JSONObject()
            .put("metadatos", metadatos)
            .put("diagnostico_biomecanico", JSONObject().put("errores_detectados_en_repeticion", errors))
            .toString()
    }
}
