package com.google.mediapipe.examples.poselandmarker.slm

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

class SlmFeedbackDialogFragment : DialogFragment() {
    private lateinit var engine: SlmEngine
    private var generationDone = false
    private var closing = false
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var missing: TextView
    private lateinit var closeButton: Button
    private lateinit var selectModelButton: Button
    private var pendingJson = ""
    private var importJob: Job? = null
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && this::selectModelButton.isInitialized) importModel(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val view = LayoutInflater.from(requireContext()).inflate(com.google.mediapipe.examples.poselandmarker.R.layout.dialog_slm_feedback, null)
        output = view.findViewById(com.google.mediapipe.examples.poselandmarker.R.id.slmOutput)
        progress = view.findViewById(com.google.mediapipe.examples.poselandmarker.R.id.slmProgress)
        status = view.findViewById(com.google.mediapipe.examples.poselandmarker.R.id.slmStatus)
        missing = view.findViewById(com.google.mediapipe.examples.poselandmarker.R.id.slmMissing)
        closeButton = view.findViewById(com.google.mediapipe.examples.poselandmarker.R.id.slmClose)
        selectModelButton = view.findViewById(com.google.mediapipe.examples.poselandmarker.R.id.slmSelectModel)
        val subtitle = view.findViewById<TextView>(com.google.mediapipe.examples.poselandmarker.R.id.slmSubtitle)
        val json = requireArguments().getString(ARG_JSON).orEmpty()
        pendingJson = json
        runCatching {
            val metadata = JSONObject(json).getJSONObject("metadatos")
            subtitle.text = "${metadata.optString("ejercicio")} · ${metadata.optInt("repeticiones")} repeticiones"
        }.onFailure { subtitle.text = "Resumen de la serie" }
        engine = SlmEngine.shared(requireContext())
        closeButton.setOnClickListener { closeSafely() }
        selectModelButton.setOnClickListener { modelPicker.launch(arrayOf("*/*")) }
        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event -> keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP }
        dialog.setOnShowListener { start(json) }
        return dialog
    }

    private fun start(json: String) = engine.generate(json, object : SlmEngine.Listener {
        override fun onState(state: SlmState) {
            if (state is SlmState.ModelMissing) {
                progress.visibility = View.GONE; status.visibility = View.GONE
                missing.visibility = View.VISIBLE
                missing.text = "No se encontró un modelo GGUF legible. Puedes importar el archivo.\n\nRuta interna esperada:\n${state.expectedPath}"
                selectModelButton.visibility = View.VISIBLE
                selectModelButton.isEnabled = true
                generationDone = true
            }
        }
        override fun onToken(piece: String) {
            progress.visibility = View.GONE; status.text = "Generando recomendación..."
            output.append(piece)
            view?.findViewById<ScrollView>(com.google.mediapipe.examples.poselandmarker.R.id.slmScroll)?.post {
                val scroll = view?.findViewById<ScrollView>(com.google.mediapipe.examples.poselandmarker.R.id.slmScroll)
                scroll?.smoothScrollTo(0, scroll.getChildAt(0).height)
            }
        }
        override fun onComplete(cancelled: Boolean) { generationDone = true; progress.visibility = View.GONE; status.visibility = View.GONE }
        override fun onError(message: String) { generationDone = true; progress.visibility = View.GONE; status.text = message }
    })

    private fun closeSafely() {
        if (closing) return
        closing = true; closeButton.isEnabled = false
        val activeImport = importJob
        if (activeImport?.isActive == true) {
            lifecycleScope.launch {
                activeImport.cancelAndJoin()
                if (isAdded) dismissAllowingStateLoss()
            }
            return
        }
        if (generationDone) dismissAllowingStateLoss()
        else engine.cancelAndWait { if (isAdded) dismissAllowingStateLoss() }
    }

    private fun importModel(uri: Uri) {
        if (importJob?.isActive == true || closing) return
        val displayName = runCatching {
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        if (!displayName.endsWith(".gguf", ignoreCase = true)) {
            status.visibility = View.VISIBLE
            status.text = "Selecciona un archivo con extensión .gguf."
            selectModelButton.visibility = View.VISIBLE
            return
        }

        selectModelButton.isEnabled = false
        selectModelButton.visibility = View.GONE
        missing.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = "Importando modelo a memoria interna (1.1 GB)..."
        progress.visibility = View.VISIBLE
        generationDone = false
        val appContext = requireContext().applicationContext
        importJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val target = File(File(appContext.filesDir, "models"), SlmConfig.MODEL_FILE)
                    val temp = File(target.parentFile, "${target.name}.importing")
                    target.parentFile?.mkdirs()
                    try {
                        val input = appContext.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado.")
                        input.use { source ->
                            temp.outputStream().buffered().use { destination ->
                                val buffer = ByteArray(65_536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    destination.write(buffer, 0, count)
                                }
                            }
                        }
                        if (temp.length() == 0L) throw IllegalStateException("El archivo GGUF está vacío.")
                        if (target.exists() && !target.delete()) throw IllegalStateException("No se pudo reemplazar el modelo anterior.")
                        if (!temp.renameTo(target)) throw IllegalStateException("No se pudo guardar el modelo en memoria interna.")
                    } catch (error: Throwable) {
                        temp.delete()
                        throw error
                    }
                }
            }
            importJob = null
            if (closing || !isAdded) return@launch
            result.onSuccess {
                output.text = ""
                missing.visibility = View.GONE
                status.text = "Modelo importado. Iniciando análisis..."
                generationDone = false
                start(pendingJson)
            }.onFailure { error ->
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = "No se pudo importar el modelo: ${error.message ?: "error de lectura"}"
                selectModelButton.visibility = View.VISIBLE
                selectModelButton.isEnabled = true
                generationDone = true
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle())
        super.onDismiss(dialog)
    }

    companion object {
        const val RESULT_KEY = "slm_feedback_closed"
        private const val ARG_JSON = "series_json"
        fun newInstance(json: String) = SlmFeedbackDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_JSON, json) }
        }
    }
}
