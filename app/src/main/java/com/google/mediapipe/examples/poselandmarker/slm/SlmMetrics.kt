package com.google.mediapipe.examples.poselandmarker.slm

import android.os.Debug
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class SlmRunMetrics(val inputTokens: Int, val outputTokens: Int, val ttftMs: Long, val totalMs: Long)

object SlmMetrics {
    private val sampler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slm-memory-metrics").apply { isDaemon = true } }
    class MemoryMonitor internal constructor(@Volatile private var peak: Long) {
        internal lateinit var task: ScheduledFuture<*>
        fun stop(): Long { task.cancel(false); peak = maxOf(peak, currentRssMb()); return peak }
        internal fun sample() { peak = maxOf(peak, currentRssMb()) }
    }
    fun monitorMemory(): MemoryMonitor {
        val monitor = MemoryMonitor(currentRssMb())
        monitor.task = sampler.scheduleAtFixedRate({ monitor.sample() }, 100, 100, TimeUnit.MILLISECONDS)
        return monitor
    }
    private fun currentRssMb(): Long {
        val rss = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024
            }
        }.getOrDefault(0L)
        return maxOf(Debug.getNativeHeapAllocatedSize() / (1024 * 1024), rss)
    }
    fun log(metrics: SlmRunMetrics, cancelled: Boolean, error: Boolean = false, peakNativeRamMb: Long? = null) {
        val decodeMs = (metrics.totalMs - metrics.ttftMs).coerceAtLeast(1L)
        val json = JSONObject()
            .put("modelo", "Qwen2.5-1.5B-Instruct-Q4_K_M")
            .put("tokens_entrada", metrics.inputTokens)
            .put("tokens_salida", metrics.outputTokens)
            .put("ttft_ms", metrics.ttftMs)
            .put("decode_tok_per_sec", metrics.outputTokens * 1000.0 / decodeMs)
            .put("total_inference_ms", metrics.totalMs)
            .put("peak_native_ram_mb", peakNativeRamMb ?: currentRssMb())
            .put("estado", when { error -> "ERROR"; cancelled -> "CANCELADO"; else -> "COMPLETADO" })
        Log.i("SLM_METRICS", json.toString())
    }
}
