package com.bhickta.faceattendance.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentAggregatorTest {
    @Test
    fun averagesConsistentSamples() {
        val result = EnrollmentAggregator.aggregate(
            List(5) { floatArrayOf(1f, 0.01f * it) },
        )

        assertNotNull(result)
        assertEquals(1f, CosineMatcher.norm(result!!.embedding), 0.0001f)
    }

    @Test
    fun rejectsTooFewOrInconsistentSamples() {
        assertNull(EnrollmentAggregator.aggregate(List(4) { floatArrayOf(1f, 0f) }))
        assertNull(
            EnrollmentAggregator.aggregate(
                listOf(
                    floatArrayOf(1f, 0f),
                    floatArrayOf(1f, 0f),
                    floatArrayOf(1f, 0f),
                    floatArrayOf(1f, 0f),
                    floatArrayOf(0f, 1f),
                ),
            ),
        )
    }
}
