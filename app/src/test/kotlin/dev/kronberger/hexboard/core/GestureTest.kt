package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GestureTest {

    /** A swipe of [length] px at [degrees] clockwise from straight up. */
    private fun swipe(degrees: Double, length: Float = 100f): Gesture {
        val rad = degrees * PI / 180.0
        return classify((sin(rad) * length).toFloat(), (-cos(rad) * length).toFloat(), thresholdPx = 50f)
    }

    @Test
    fun shortMovesAreTaps() {
        assertEquals(Gesture.Tap, classify(0f, 0f, 50f))
        assertEquals(Gesture.Tap, swipe(45.0, length = 49f))
    }

    @Test
    fun sectorCentersMapToTheirDirection() {
        val expected = listOf(
            0.0 to Direction.UP,
            60.0 to Direction.UP_RIGHT,
            120.0 to Direction.DOWN_RIGHT,
            180.0 to Direction.DOWN,
            240.0 to Direction.DOWN_LEFT,
            300.0 to Direction.UP_LEFT,
        )
        for ((deg, dir) in expected) assertEquals("at $deg°", Gesture.Swipe(dir), swipe(deg))
    }

    @Test
    fun upCoversThirtyDegreesEitherSide() {
        assertEquals(Gesture.Swipe(Direction.UP), swipe(29.0))
        assertEquals(Gesture.Swipe(Direction.UP), swipe(331.0))
        assertEquals(Gesture.Swipe(Direction.UP_RIGHT), swipe(31.0))
        assertEquals(Gesture.Swipe(Direction.UP_LEFT), swipe(329.0))
    }

    @Test
    fun screenYGrowsDownwards() {
        assertEquals(Gesture.Swipe(Direction.DOWN), classify(0f, 80f, 50f))
        assertEquals(Gesture.Swipe(Direction.UP), classify(0f, -80f, 50f))
    }
}
