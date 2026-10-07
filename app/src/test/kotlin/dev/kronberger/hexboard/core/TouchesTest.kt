package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchesTest {

    private val keys = Layout.parse(listOf("a b c")).keys
    private val a = keys[0]
    private val b = keys[1]
    private val c = keys[2]
    private val tap = Gesture.Tap

    private fun touches() = Touches(thresholdPx = 50f)

    @Test
    fun singleTapFinishesOnRelease() {
        val t = touches()
        assertEquals(emptyList<Press>(), t.down(0, a, 0f, 0f, emptyMap()))
        assertEquals(Press(a, tap), t.up(0, 2f, 3f))
    }

    @Test
    fun rollingKeepsTouchDownOrder() {
        val t = touches()
        t.down(0, a, 0f, 0f, emptyMap())
        val early = t.down(1, b, 100f, 0f, mapOf(0 to Point(0f, 0f)))
        assertEquals(listOf(Press(a, tap)), early)
        assertEquals(Press(b, tap), t.up(1, 100f, 0f))
        // The first finger lifting afterwards does not type twice.
        assertNull(t.up(0, 0f, 0f))
    }

    @Test
    fun threeFingerRollComesOutInOrder() {
        val t = touches()
        val out = mutableListOf<Press>()
        out += t.down(0, a, 0f, 0f, emptyMap())
        out += t.down(1, b, 100f, 0f, mapOf(0 to Point(0f, 0f)))
        out += t.down(2, c, 200f, 0f, mapOf(0 to Point(0f, 0f), 1 to Point(100f, 0f)))
        t.up(0, 0f, 0f)?.let { out += it }
        t.up(1, 100f, 0f)?.let { out += it }
        t.up(2, 200f, 0f)?.let { out += it }
        assertEquals(listOf(a, b, c), out.map { it.key })
    }

    @Test
    fun swipeInProgressIsJudgedWhereTheFingerIsNow() {
        val t = touches()
        t.down(0, a, 0f, 100f, emptyMap())
        val early = t.down(1, b, 100f, 0f, mapOf(0 to Point(0f, 20f)))
        assertEquals(listOf(Press(a, Gesture.Swipe(Direction.UP))), early)
    }

    @Test
    fun cancelDropsEveryFinger() {
        val t = touches()
        t.down(0, a, 0f, 0f, emptyMap())
        t.cancel()
        assertNull(t.up(0, 0f, 0f))
    }

    @Test
    fun touchOffEveryKeyIsIgnored() {
        val t = touches()
        assertEquals(emptyList<Press>(), t.down(0, null, 0f, 0f, emptyMap()))
        assertNull(t.up(0, 0f, 0f))
    }
}
