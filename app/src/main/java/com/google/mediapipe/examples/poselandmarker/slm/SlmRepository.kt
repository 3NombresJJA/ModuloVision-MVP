package com.google.mediapipe.examples.poselandmarker.slm

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

sealed class SlmState {
    data class ModelMissing(val expectedPath: String) : SlmState()
    object Ready : SlmState()
    data class Error(val message: String) : SlmState()
}

class SlmRepository(context: Context) {
    private val appContext = context.applicationContext
    val internalModelFile = File(File(appContext.filesDir, "models"), SlmConfig.MODEL_FILE)
    private val externalModelFile = appContext.getExternalFilesDir(null)
        ?.let { File(File(it, "models"), SlmConfig.MODEL_FILE) }
    @Volatile private var selectedModelFile: File = internalModelFile

    init { internalModelFile.parentFile?.mkdirs() }

    @Synchronized fun checkModel(): SlmState {
        val internalReadable = logCandidate(internalModelFile)
        val externalReadable = externalModelFile?.let(::logCandidate) ?: false
        selectedModelFile = when {
            internalReadable -> internalModelFile
            externalReadable -> requireNotNull(externalModelFile)
            else -> internalModelFile
        }
        return if (internalReadable || externalReadable) SlmState.Ready
        else SlmState.ModelMissing(internalModelFile.absolutePath)
    }

    fun modelPath(): String = selectedModelFile.absolutePath

    private fun logCandidate(file: File): Boolean {
        val exists = file.exists()
        val canRead = file.canRead()
        val bytes = if (exists && file.isFile) file.length() else 0L
        Log.i("SLM_METRICS", JSONObject()
            .put("ruta", file.absolutePath)
            .put("exists", exists)
            .put("canRead", canRead)
            .put("bytes", bytes)
            .toString())
        return exists && file.isFile && canRead && bytes > 0L
    }
}
