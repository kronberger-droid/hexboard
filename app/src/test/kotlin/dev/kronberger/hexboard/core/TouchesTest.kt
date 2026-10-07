package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchesTest {

    private val keys = Layout.parse(listOf("a b c ⌫")).keys
    private val a = keys[0]
    private val b = keys[1]
    private val c = keys[2]
    private val del = keys[3]
    private val tap = Gesture.Tap
    private val none = emptyList<TouchEvent>()

    // Threshold 50px, one cluster per 20px of scrub.
    private fun touches() = Touches(thresholdPx = 50f, scrubStepPx = 20f)

    private fun press(key: Key, g: Gesture = tap) = TouchEvent.Press(key, g)
    private fun act(action: KeyAction) = TouchEvent.Act(action)

    @Test
    fun singleTapFinishesOnRelease() {
        val t = touches()
        assertEquals(none, t.down(0, a, 0f, 0f, emptyMap()))
        assertEquals(listOf(press(a)), t.up(0, 2f, 3f))
    }

    @Test
    fun rollingKeepsTouchDownOrder() {
        val t = touches()
        t.down(0, a, 0f, 0f, emptyMap())
        assertEquals(listOf(press(a)), t.down(1, b, 100f, 0f, mapOf(0 to Point(0f, 0f))))
        assertEquals(listOf(press(b)), t.up(1, 100f, 0f))
        // The first finger lifting afterwards does not type twice.
        assertEquals(none, t.up(0, 0f, 0f))
    }

    @Test
    fun threeFingerRollComesOutInOrder() {
        val t = touches()
        val out = mutableListOf<TouchEvent>()
        out += t.down(0, a, 0f, 0f, emptyMap())
        out += t.down(1, b, 100f, 0f, mapOf(0 to Point(0f, 0f)))
        out += t.down(2, c, 200f, 0f, mapOf(0 to Point(0f, 0f), 1 to Point(100f, 0f)))
        out += t.up(0, 0f, 0f)
        out += t.up(1, 100f, 0f)
        out += t.up(2, 200f, 0f)
        assertEquals(listOf(press(a), press(b), press(c)), out)
    }

    @Test
    fun swipeInProgressIsJudgedWhereTheFingerIsNow() {
        val t = touches()
        t.down(0, a, 0f, 100f, emptyMap())
        val early = t.down(1, b, 100f, 0f, mapOf(0 to Point(0f, 20f)))
        assertEquals(listOf(press(a, Gesture.Swipe(Direction.UP))), early)
    }

    @Test
    fun touchOffEveryKeyIsIgnored() {
        val t = touches()
        assertEquals(none, t.down(0, null, 0f, 0f, emptyMap()))
        assertEquals(none, t.up(0, 0f, 0f))
    }

    @Test
    fun backspaceTapIsAPress() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        assertEquals(none, t.move(0, 480f, 0f))
        assertEquals(listOf(press(del)), t.up(0, 480f, 0f))
    }

    @Test
    fun scrubSelectsOneClusterPerStepAndShrinksBack() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        assertEquals(listOf(act(KeyAction.ScrubTo(2))), t.move(0, 450f, 0f))
        assertEquals(none, t.move(0, 445f, 0f))
        assertEquals(listOf(act(KeyAction.ScrubTo(5))), t.move(0, 400f, 0f))
        assertEquals(listOf(act(KeyAction.ScrubTo(1))), t.move(0, 475f, 0f))
        assertEquals(listOf(act(KeyAction.ScrubEnd(1))), t.up(0, 475f, 0f))
    }

    @Test
    fun scrubDraggedPastItsStartSelectsNothing() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        t.move(0, 440f, 0f)
        assertEquals(listOf(act(KeyAction.ScrubTo(0))), t.move(0, 560f, 0f))
        assertEquals(listOf(act(KeyAction.ScrubEnd(0))), t.up(0, 560f, 0f))
    }

    @Test
    fun fingersLandingDuringAScrubAreIgnored() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        t.move(0, 440f, 0f)
        assertEquals(none, t.down(1, a, 0f, 0f, mapOf(0 to Point(440f, 0f))))
        assertEquals(none, t.up(1, 0f, 0f))
        assertEquals(listOf(act(KeyAction.ScrubEnd(3))), t.up(0, 440f, 0f))
    }

    @Test
    fun backspaceNotYetScrubbingIsFinishedLikeAnyKey() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        assertEquals(listOf(press(del)), t.down(1, a, 0f, 0f, mapOf(0 to Point(500f, 0f))))
    }

    @Test
    fun swipeRightOnBackspaceRecalls() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        assertEquals(listOf(act(KeyAction.Recall)), t.up(0, 560f, 10f))
    }

    @Test
    fun cancelEndsAScrubWithoutDeleting() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        t.move(0, 440f, 0f)
        assertEquals(listOf(act(KeyAction.ScrubEnd(0))), t.cancel())
        assertEquals(none, t.up(0, 440f, 0f))
    }
}
