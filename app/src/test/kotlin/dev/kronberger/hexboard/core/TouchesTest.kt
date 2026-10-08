package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // Threshold 50px. Past it, drags follow the finger one cluster per 10px
    // for three clusters, then speed up from 30px past it to 100/s at 130px.
    private val curve = DragCurve(stepPx = 10f, fineSteps = 3, rampPx = 100f, maxPerSecond = 100f)
    private fun touches() = Touches(thresholdPx = 50f, curve = curve)

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
    fun curveStepsByPositionThenSpeedsUpFromZero() {
        assertEquals(1, curve.steps(0f))
        assertEquals(1, curve.steps(9.9f))
        assertEquals(2, curve.steps(10f))
        assertEquals(3, curve.steps(500f))
        assertEquals(0f, curve.speed(30f), 0f)
        assertEquals(25f, curve.speed(80f), 1e-3f)
        assertEquals(100f, curve.speed(500f), 0f)
    }

    @Test
    fun scrubFollowsTheFingerNearTheStart() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 445f, 0f, timeMs = 0))
        assertTrue(t.dragging)
        t.move(0, 425f, 0f, timeMs = 50)
        assertEquals(listOf(by(Drag.SCRUB, 2)), t.tick(100))
        // Holding still stays put however long it lasts.
        assertEquals(none, t.tick(5000))
        t.move(0, 445f, 0f, timeMs = 5000)
        assertEquals(listOf(by(Drag.SCRUB, -2)), t.tick(5100))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 445f, 0f))
        assertFalse(t.dragging)
    }

    @Test
    fun farOutTheDragSpeedsUpWithDistance() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        // 120px past the threshold: three steps plus 81 clusters/s.
        t.move(0, 330f, 0f, timeMs = 0)
        assertEquals(listOf(by(Drag.SCRUB, 10)), t.tick(100))
        // 170px past it is beyond the ramp: 100/s.
        t.move(0, 280f, 0f, timeMs = 100)
        assertEquals(listOf(by(Drag.SCRUB, 10)), t.tick(200))
    }

    @Test
    fun comingBackGivesUpThePositionStepsAndCrossingTheStartReverses() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        t.move(0, 280f, 0f, timeMs = 0)
        assertEquals(listOf(by(Drag.SCRUB, 12)), t.tick(100))
        // Back at the start, only what speed covered remains.
        t.move(0, 500f, 0f, timeMs = 100)
        assertEquals(listOf(by(Drag.SCRUB, -3)), t.tick(200))
        assertEquals(none, t.tick(1200))
        // 50px past the threshold on the other side: three steps back, and 4/s.
        t.move(0, 600f, 0f, timeMs = 1200)
        assertEquals(listOf(by(Drag.SCRUB, -7)), t.tick(2200))
    }

    @Test
    fun recallGoesRightAndKeepsGoingWhileHeldFarOut() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.RECALL, 1)), t.move(0, 555f, 0f, timeMs = 0))
        t.move(0, 680f, 0f, timeMs = 0)
        assertEquals(listOf(by(Drag.RECALL, 22)), t.tick(200))
        assertEquals(listOf(by(Drag.RECALL, 20)), t.tick(400))
    }

    @Test
    fun liftingLandsWhereTheFingerIsEvenBetweenFrames() {
        val t = touches()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(listOf(by(Drag.SCRUB, 2), end(Drag.SCRUB)), t.up(0, 420f, 0f))
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
        t.move(0, 435f, 0f, timeMs = 50)
        assertEquals(listOf(by(Drag.MOVE, -1)), t.tick(100))
        t.up(0, 435f, 0f)
        t.down(1, selectSpace, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SELECT, 1)), t.move(1, 555f, 0f, timeMs = 0))
        t.move(1, 565f, 0f, timeMs = 50)
        assertEquals(listOf(by(Drag.SELECT, 1)), t.tick(100))
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
        assertFalse(t.dragging)
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
        assertFalse(t.dragging)
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
