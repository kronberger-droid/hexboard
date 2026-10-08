package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchesTest {

    private val keys = Layout.parse(
        listOf("a b c ⌫ ,/. ␣:select ␣:move"),
        alternates = mapOf(",/." to mapOf(Direction.UP_LEFT to "\"")),
    ).keys
    private val a = keys[0]
    private val b = keys[1]
    private val c = keys[2]
    private val del = keys[3]
    private val punct = keys[4]
    private val selectSpace = keys[5]
    private val moveSpace = keys[6]
    private val tap = Gesture.Tap
    private val none = emptyList<TouchEvent>()

    // Threshold 50px. Drags move one cluster per 10px up to 100px/s, easing
    // up to eleven times that at 1100px/s. No smoothing, so each move's
    // speed is exactly its own.
    private val gain = DragGain(stepPx = 10f, slowPxPerS = 100f, fastPxPerS = 1100f, maxGain = 11f, smoothMs = 0f)
    private fun touches(flickMs: Long = 0) = Touches(thresholdPx = 50f, gain = gain, flickMs = flickMs)

    private fun press(key: Key, g: Gesture = tap) = TouchEvent.Press(key, g)
    private fun by(drag: Drag, delta: Int) = TouchEvent.Act(KeyAction.DragBy(drag, delta))
    private fun end(drag: Drag, keep: Boolean = true) = TouchEvent.Act(KeyAction.DragEnd(drag, keep))

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
    fun gainEasesFromTheSlowStepToItsMaximum() {
        assertEquals(0.1f, gain.at(0f), 1e-6f)
        assertEquals(0.1f, gain.at(100f), 1e-6f)
        assertEquals(0.6f, gain.at(600f), 1e-6f)
        assertEquals(1.1f, gain.at(5000f), 1e-6f)
    }

    @Test
    fun slowDragMovesOneClusterPerStepAndOnlyWhileMoving() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 445f, 0f, timeMs = 0))
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 435f, 0f, timeMs = 100))
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 425f, 0f, timeMs = 200))
        assertEquals(listOf(by(Drag.SCRUB, -1)), t.move(0, 435f, 0f, timeMs = 300))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 435f, 0f))
    }

    @Test
    fun fastSwipeCoversManyAndTheSameSwipeBackUndoesThem() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        // 100px in 50ms is 2000px/s, past the top of the ramp.
        assertEquals(listOf(by(Drag.SCRUB, 110)), t.move(0, 345f, 0f, timeMs = 50))
        // Slowing down is fine-grained again straight away.
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 335f, 0f, timeMs = 150))
        assertEquals(listOf(by(Drag.SCRUB, -1)), t.move(0, 345f, 0f, timeMs = 250))
        assertEquals(listOf(by(Drag.SCRUB, -110)), t.move(0, 445f, 0f, timeMs = 300))
    }

    @Test
    fun jitterSmallerThanAStepNeverFlickers() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        for (i in 1..6) {
            val x = if (i % 2 == 1) 441f else 445f
            assertEquals(none, t.move(0, x, 0f, timeMs = i * 100L))
        }
    }

    @Test
    fun fastFlickLiftedInsideTheWindowMovesExactlyOne() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 445f, 0f, timeMs = 0))
        assertEquals(none, t.move(0, 345f, 0f, timeMs = 50))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 300f, 0f))
    }

    @Test
    fun dragOutlastingTheFlickWindowCatchesUp() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(none, t.move(0, 345f, 0f, timeMs = 50))
        assertEquals(listOf(by(Drag.SCRUB, 111)), t.move(0, 335f, 0f, timeMs = 150))
        assertEquals(listOf(by(Drag.SCRUB, 1), end(Drag.SCRUB)), t.up(0, 325f, 0f))
    }

    @Test
    fun recallGoesRightWithTheFinger() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.RECALL, 1)), t.move(0, 555f, 0f, timeMs = 0))
        assertEquals(listOf(by(Drag.RECALL, 110)), t.move(0, 655f, 0f, timeMs = 50))
    }

    @Test
    fun liftingLandsWhereTheFingerIsEvenWithoutAMove() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        t.move(0, 435f, 0f, timeMs = 100)
        assertEquals(listOf(by(Drag.SCRUB, 1), end(Drag.SCRUB)), t.up(0, 425f, 0f))
    }

    @Test
    fun quickFlickMovesExactlyOne() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SCRUB, 1), end(Drag.SCRUB)), t.up(0, 430f, 0f))
        t.down(1, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.RECALL, 1), end(Drag.RECALL)), t.up(1, 570f, 0f))
    }

    @Test
    fun spacesMoveTheCursorOrSelectInEitherDirection() {
        val t = touches()
        t.down(0, moveSpace, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.MOVE, -1)), t.move(0, 445f, 0f, timeMs = 0))
        assertEquals(listOf(by(Drag.MOVE, -1)), t.move(0, 435f, 0f, timeMs = 100))
        t.up(0, 435f, 0f)
        t.down(1, selectSpace, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SELECT, 1)), t.move(1, 555f, 0f, timeMs = 0))
        assertEquals(listOf(by(Drag.SELECT, 1)), t.move(1, 565f, 0f, timeMs = 100))
        assertEquals(listOf(end(Drag.SELECT)), t.up(1, 565f, 0f))
    }

    @Test
    fun tappingASpaceIsStillAPress() {
        val t = touches()
        t.down(0, moveSpace, 500f, 0f, emptyMap())
        assertEquals(listOf(press(moveSpace)), t.up(0, 505f, 0f))
    }

    @Test
    fun dragIsNotFinishedByAnotherFingerWhichIsIgnored() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(none, t.down(1, a, 0f, 0f, mapOf(0 to Point(445f, 0f))))
        assertEquals(none, t.up(1, 0f, 0f))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 445f, 0f))
    }

    @Test
    fun keyNotYetDraggingIsFinishedLikeAnyKey() {
        val t = touches()
        t.down(0, del, 500f, 0f, emptyMap())
        assertEquals(listOf(press(del)), t.down(1, a, 0f, 0f, mapOf(0 to Point(500f, 0f))))
    }

    @Test
    fun upSwipeThatDriftsLeftStaysUp() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(none, t.move(0, 500f, -60f, timeMs = 0))
        assertEquals(none, t.move(0, 380f, -60f, timeMs = 10))
        assertEquals(listOf(press(a, Gesture.Swipe(Direction.UP))), t.up(0, 380f, -60f))
    }

    @Test
    fun plainKeysReadSteepDiagonalsAsUpOrDown() {
        val t = touches()
        t.down(0, a, 0f, 0f, emptyMap())
        // 40° right of straight up.
        assertEquals(listOf(press(a, Gesture.Swipe(Direction.UP))), t.up(0, 64f, -77f))
    }

    @Test
    fun keysWithAlternatesKeepSixDirectionsAndNeverDrag() {
        val t = touches()
        t.down(0, punct, 500f, 0f, emptyMap())
        assertEquals(none, t.move(0, 440f, -20f, timeMs = 0))
        assertEquals(listOf(press(punct, Gesture.Swipe(Direction.UP_LEFT))), t.up(0, 440f, -20f))
    }

    @Test
    fun cancelEndsADragWithoutKeepingIt() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 440f, 0f, timeMs = 0)
        assertEquals(listOf(end(Drag.SCRUB, keep = false)), t.cancel())
        assertEquals(none, t.up(0, 440f, 0f))
    }
}
