package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTest {

    @Test
    fun portraitUsesRowPitch() {
        assertEquals(560, keyboardHeightPx(rows = 3, screenHeightPx = 2400, density = 3f))
    }

    @Test
    fun landscapeIsCappedByScreenFraction() {
        assertEquals(486, keyboardHeightPx(rows = 3, screenHeightPx = 1080, density = 3f))
    }
}
