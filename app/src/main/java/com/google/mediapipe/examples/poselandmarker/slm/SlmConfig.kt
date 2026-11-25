package com.google.mediapipe.examples.poselandmarker.slm

object SlmConfig {
    const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val MAX_OUTPUT_TOKENS = 180
    const val CONTEXT_SIZE = 2048
    const val NUM_THREADS = 4
    const val TEMPERATURE = 0.25f
    const val TOP_P = 0.85f
    const val REPEAT_PENALTY = 1.10f
}
