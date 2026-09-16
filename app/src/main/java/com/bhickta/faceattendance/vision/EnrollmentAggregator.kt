package com.bhickta.faceattendance.vision

data class AggregatedEnrollment(
    val embedding: FloatArray,
    val minimumSimilarity: Float,
)

object EnrollmentAggregator {
    const val REQUIRED_SAMPLES = 5
    private const val MINIMUM_SAMPLE_SIMILARITY = 0.65f

    fun aggregate(samples: List<FloatArray>): AggregatedEnrollment? {
        if (samples.size < REQUIRED_SAMPLES) return null
        val dimensions = samples.first().size
        if (dimensions == 0 || samples.any { it.size != dimensions || it.any { value -> !value.isFinite() } }) {
            return null
        }
        var minimum = 1f
        for (left in samples.indices) {
            for (right in left + 1 until samples.size) {
                minimum = minOf(minimum, cosine(samples[left], samples[right]))
            }
        }
        if (minimum < MINIMUM_SAMPLE_SIMILARITY) return null
        val average = FloatArray(dimensions)
        samples.forEach { sample -> sample.indices.forEach { average[it] += sample[it] } }
        return AggregatedEnrollment(CosineMatcher.normalize(average), minimum)
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        val a = CosineMatcher.normalize(left)
        val b = CosineMatcher.normalize(right)
        return a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
    }
}
