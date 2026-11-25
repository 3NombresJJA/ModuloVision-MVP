package com.google.mediapipe.examples.poselandmarker.slm

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Owns the warm native model and serializes every llama.cpp operation. */
class SlmEngine(context: Context) {
    private val repository = SlmRepository(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "slm-native") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var handle = 0L
    @Volatile private var running = false
    @Volatile private var cancelRequested = false
    private val stateLock = Any()
    private var generation: Future<*>? = null

    fun generate(json: String, listener: Listener) {
        val state = repository.checkModel()
        if (state !is SlmState.Ready) {
            listener.onState(state)
            return
        }
        listener.onState(SlmState.Ready)
        cancelRequested = false
        generation = executor.submit {
            val memoryMonitor = SlmMetrics.monitorMemory()
            try {
                if (handle == 0L) handle = LlamaNativeBridge.nativeLoadModel(
                    repository.modelPath(), SlmConfig.CONTEXT_SIZE, SlmConfig.NUM_THREADS
                )
                if (handle == 0L) throw IllegalStateException("No se pudo cargar el modelo SLM.")
                synchronized(stateLock) {
                    if (cancelRequested) {
                        SlmMetrics.log(SlmRunMetrics(0, 0, 0, 0), cancelled = true, peakNativeRamMb = memoryMonitor.stop())
                        main.post { listener.onComplete(true) }
                        return@submit
                    }
                    LlamaNativeBridge.nativeResetCancel(handle)
                    running = true
                }
                LlamaNativeBridge.nativeGenerateStream(handle, SlmPromptBuilder.build(json), SlmConfig.MAX_OUTPUT_TOKENS,
                    object : LlamaNativeBridge.NativeCallback {
                        override fun onToken(piece: String) = main.post { listener.onToken(piece) }.let { Unit }
                        override fun onComplete(inputTokens: Int, outputTokens: Int, ttftMs: Long, totalMs: Long, cancelled: Boolean) {
                            synchronized(stateLock) { running = false }
                            SlmMetrics.log(SlmRunMetrics(inputTokens, outputTokens, ttftMs, totalMs), cancelled, peakNativeRamMb = memoryMonitor.stop())
                            main.post { listener.onComplete(cancelled) }
                        }
                        override fun onError(message: String) {
                            synchronized(stateLock) { running = false }
                            SlmMetrics.log(SlmRunMetrics(0, 0, 0, 0), cancelRequested, error = true, peakNativeRamMb = memoryMonitor.stop())
                            main.post { listener.onError(message) }
                        }
                    })
            } catch (e: Exception) {
                synchronized(stateLock) { running = false }
                SlmMetrics.log(SlmRunMetrics(0, 0, 0, 0), cancelRequested, error = true, peakNativeRamMb = memoryMonitor.stop())
                main.post { listener.onError(e.message ?: "Error de inferencia") }
            }
        }
    }

    /** Enqueues cancel behind the current JNI call; completion means native code has returned. */
    fun cancelAndWait(onDone: () -> Unit) {
        synchronized(stateLock) {
            cancelRequested = true
            val activeHandle = handle
            if (activeHandle != 0L && running) runCatching { LlamaNativeBridge.nativeCancel(activeHandle) }
        }
        executor.submit { running = false; main.post(onDone) }
    }

    interface Listener {
        fun onState(state: SlmState)
        fun onToken(piece: String)
        fun onComplete(cancelled: Boolean)
        fun onError(message: String)
    }

    companion object {
        @Volatile private var instance: SlmEngine? = null
        fun shared(context: Context): SlmEngine = instance ?: synchronized(this) {
            instance ?: SlmEngine(context.applicationContext).also { instance = it }
        }
    }
}
