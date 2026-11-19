package com.google.mediapipe.examples.poselandmarker

import java.util.ArrayDeque

/** Fixed-size chronological angle history; no JSON or frame string allocation occurs here. */
class AngleTrajectoryBuffer(private val capacity: Int = 11) {
    init { require(capacity > 0) }
    private val buffer = ArrayDeque<Map<String, Int>>(capacity)

    @Synchronized fun addFrame(angles: Map<String, Int>) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(angles.toMap())
    }

    @Synchronized fun clear() = buffer.clear()

    @Synchronized fun extractSubsampledTrajectory(): Map<String, List<Int>> {
        if (buffer.isEmpty()) return emptyMap()
        val frames = buffer.toList()
        val size = frames.size
        val indices = if (size == capacity) listOf(1, 4, 7, 10) else {
            val step = if (size >= 10) 3 else maxOf(1, size / 4)
            val selected = ArrayList<Int>(4)
            var index = size - 1
            while (index >= 0 && selected.size < 4) { selected += index; index -= step }
            selected.reverse()
            selected
        }
        val result = LinkedHashMap<String, List<Int>>(frames.last().size)
        for (joint in frames.last().keys) result[joint] = indices.mapNotNull { frames[it][joint] }
        return result
    }
}
