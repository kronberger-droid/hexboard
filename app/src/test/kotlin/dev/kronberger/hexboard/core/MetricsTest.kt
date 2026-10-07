package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTest {

    @Test
    fun portraitUsesPreferredHeight() {
        assertEquals(780, keyboardHeightPx(screenHeightPx = 2400, density = 3f))
    }

    @Test
    fun landscapeIsCappedByScreenFraction() {
        assertEquals(486, keyboardHeightPx(screenHeightPx = 1080, density = 3f))
    }
}
