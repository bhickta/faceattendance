package com.bhickta.faceattendance.vision

import kotlin.math.sqrt

data class TemplateMatch(val template: FaceTemplate, val similarity: Float)

object CosineMatcher {
    fun best(
        probe: FloatArray,
        templates: List<FaceTemplate>,
        threshold: Float,
        minimumMargin: Float,
    ): TemplateMatch? {
        if (templates.isEmpty()) return null
        val ranked = templates.map { TemplateMatch(it, cosine(probe, it.embedding)) }
            .sortedByDescending(TemplateMatch::similarity)
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.similarity ?: -1f
        return best.takeIf { it.similarity >= threshold && it.similarity - runnerUp >= minimumMargin }
    }

    fun normalize(values: FloatArray): FloatArray {
        val length = norm(values)
        require(length > 0f && length.isFinite())
        return FloatArray(values.size) { values[it] / length }
    }

    fun norm(values: FloatArray): Float = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size)
        var dot = 0.0
        var leftSquared = 0.0
        var rightSquared = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftSquared += left[index] * left[index]
            rightSquared += right[index] * right[index]
        }
        val denominator = sqrt(leftSquared * rightSquared)
        return if (denominator == 0.0) -1f else (dot / denominator).toFloat()
    }
}
