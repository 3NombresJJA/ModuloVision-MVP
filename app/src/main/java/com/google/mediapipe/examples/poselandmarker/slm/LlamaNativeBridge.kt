package com.google.mediapipe.examples.poselandmarker.slm

internal object LlamaNativeBridge {
    init { System.loadLibrary("slm_jni") }
    external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun nativeGenerateStream(handle: Long, prompt: String, maxTokens: Int, callback: NativeCallback)
    external fun nativeCancel(handle: Long)
    external fun nativeResetCancel(handle: Long)
    external fun nativeRelease(handle: Long)
    interface NativeCallback {
        fun onToken(piece: String)
        fun onComplete(inputTokens: Int, outputTokens: Int, ttftMs: Long, totalMs: Long, cancelled: Boolean)
        fun onError(message: String)
    }
}
