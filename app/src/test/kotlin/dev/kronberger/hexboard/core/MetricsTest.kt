package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsTest {

    @Test
    fun portraitFollowsWidth() {
        // Seven hex widths across, eight radii down: 1080 * 8 / (7 * sqrt 3).
        assertEquals(713, keyboardHeightPx(Layouts.letters, widthPx = 1080, screenHeightPx = 2400))
    }

    @Test
    fun landscapeIsCappedByScreenFraction() {
        assertEquals(486, keyboardHeightPx(Layouts.letters, widthPx = 2392, screenHeightPx = 1080))
    }
}
