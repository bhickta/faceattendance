package com.bhickta.faceattendance.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CosineMatcherTest {
    @Test
    fun returnsDistinctBestMatch() {
        val match = CosineMatcher.best(
            probe = floatArrayOf(1f, 0f),
            templates = listOf(
                template("expected", floatArrayOf(0.99f, 0.01f)),
                template("other", floatArrayOf(0.5f, 0.5f)),
            ),
            threshold = 0.7f,
            minimumMargin = 0.1f,
        )

        assertEquals("expected", match?.template?.personId)
    }

    @Test
    fun rejectsAmbiguousMatch() {
        val match = CosineMatcher.best(
            probe = floatArrayOf(1f, 0f),
            templates = listOf(
                template("first", floatArrayOf(1f, 0f)),
                template("second", floatArrayOf(0.999f, 0.001f)),
            ),
            threshold = 0.7f,
            minimumMargin = 0.08f,
        )

        assertNull(match)
    }

    private fun template(id: String, embedding: FloatArray) = FaceTemplate(
        personId = id,
        displayName = id,
        templateVersion = "1",
        embedding = embedding,
    )
}
