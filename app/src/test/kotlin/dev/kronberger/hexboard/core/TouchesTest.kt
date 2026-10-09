package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchesTest {

    private val keys = Layout.parse(
        listOf("a b c ⌫ ,/.:fixed ␣:select ␣:move o e"),
        alternates = mapOf(
            ",/." to mapOf(Direction.UP_LEFT to "\""),
            "e" to mapOf(Direction.UP_LEFT to "é", Direction.DOWN_RIGHT to "è"),
        ),
        longPress = mapOf("o" to "ö"),
    ).keys
    private val a = keys[0]
    private val b = keys[1]
    private val c = keys[2]
    private val del = keys[3]
    private val punct = keys[4]
    private val selectSpace = keys[5]
    private val moveSpace = keys[6]
    private val o = keys[7]
    private val e = keys[8]
    private val tap = Gesture.Tap
    private val none = emptyList<TouchEvent>()

    // Threshold 50px. Drags move one cluster per 10px up to 100px/s, easing
    // up to eleven times that at 1100px/s. No smoothing, so each move's
    // speed is exactly its own.
    private val gain = DragGain(stepPx = 10f, slowPxPerS = 100f, fastPxPerS = 1100f, maxGain = 11f, smoothMs = 0f)
    // Edge strips start at 10 clusters/s and reach 110/s after a second held.
    // Held delete repeats from 10/s up to 110/s after a second.
    private val repeat = Ramp(startPerS = 10f, maxPerS = 110f, rampMs = 1000f)
    private val edge = DragEdge(zonePx = 100f, startPerS = 10f, maxPerS = 110f, rampMs = 1000f)
    // Flicks, where tested, are drags faster than 300px/s lifted within 150ms.
    // Long presses fire after 300ms.
    private fun touches(flickMs: Long = 0) = Touches(50f, gain, flickMs, flickPxPerS = 300f, edge = edge, holdMs = 300, repeat = repeat)

    // A keyboard 1000px wide with edge strips of 100px, and a flat gain of
    // one cluster per 10px so only the edge changes speed.
    private fun edged(flickMs: Long = 0) =
        Touches(50f, DragGain(10f, 1e6f, 2e6f, 1f, 0f), flickMs, 300f, edge, holdMs = 300, repeat = repeat).also { it.span(0f, 1000f) }

    private fun press(key: Key, g: Gesture = tap) = TouchEvent.Press(key, g)
    private fun by(drag: Drag, delta: Int) = TouchEvent.Act(KeyAction.DragBy(drag, delta))
    private fun words(drag: Drag, delta: Int) = TouchEvent.Act(KeyAction.DragBy(drag, delta, words = true))
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
    fun fastFlickLiftedInsideTheWindowMovesExactlyOneAndShowsNoMore() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 445f, 0f, timeMs = 0))
        assertEquals(none, t.move(0, 345f, 0f, timeMs = 50))
        assertEquals(none, t.tick(100))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 300f, 0f))
    }

    @Test
    fun flickIsJudgedFromTheSampleThatCrossedTheThreshold() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        assertEquals(none, t.move(0, 480f, 0f, timeMs = 0))
        // 40px in 10ms: fast from the start.
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 440f, 0f, timeMs = 10))
        assertEquals(none, t.move(0, 340f, 0f, timeMs = 40))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 340f, 0f))
    }

    @Test
    fun flickSlowingDownBeforeItLiftsKeepsWhatItHeldHidden() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(none, t.move(0, 345f, 0f, timeMs = 50))
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 335f, 0f, timeMs = 150 - 1))
        assertEquals(listOf(by(Drag.SCRUB, -1), end(Drag.SCRUB)), t.up(0, 335f, 0f))
    }

    @Test
    fun slowStartShowsItsMovesAtOnce() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 480f, 0f, timeMs = 0)
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 445f, 0f, timeMs = 200))
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 435f, 0f, timeMs = 300))
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 425f, 0f, timeMs = 400))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 425f, 0f))
    }

    @Test
    fun slowDragLiftedInsideTheWindowIsStillAFlick() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 435f, 0f, timeMs = 100))
        assertEquals(listOf(by(Drag.SCRUB, -1), end(Drag.SCRUB)), t.up(0, 435f, 0f))
    }

    @Test
    fun fastDragOutlastingTheFlickWindowCatchesUp() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(none, t.move(0, 345f, 0f, timeMs = 50))
        assertEquals(listOf(by(Drag.SCRUB, 110)), t.tick(150))
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 335f, 0f, timeMs = 250))
        assertEquals(listOf(by(Drag.SCRUB, 1), end(Drag.SCRUB)), t.up(0, 325f, 0f))
    }

    @Test
    fun holdingInTheEdgeStripKeepsGoingFasterTheLongerItStays() {
        val t = edged()
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(listOf(by(Drag.SCRUB, 30)), t.move(0, 145f, 0f, timeMs = 400))
        assertEquals(listOf(by(Drag.SCRUB, 10)), t.move(0, 45f, 0f, timeMs = 500))
        // 100ms in: 11 clusters/s. A second in: 110/s.
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.tick(600))
        assertEquals(listOf(by(Drag.SCRUB, 99)), t.tick(1500))
        // Deeper in changes nothing.
        assertEquals(listOf(by(Drag.SCRUB, 4)), t.move(0, 5f, 0f, timeMs = 1500))
        assertEquals(listOf(by(Drag.SCRUB, 11)), t.tick(1600))
        // Out of the strip it stops; back in, it starts slow again.
        assertEquals(listOf(by(Drag.SCRUB, -14)), t.move(0, 145f, 0f, timeMs = 1700))
        assertEquals(none, t.tick(2700))
        assertEquals(listOf(by(Drag.SCRUB, 10)), t.move(0, 45f, 0f, timeMs = 2800))
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.tick(2900))
    }

    @Test
    fun keyStartingInTheStripOnlyRunsOnceDraggedTowardsTheEdge() {
        val t = edged()
        t.down(0, selectSpace, 920f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SELECT, -1)), t.move(0, 865f, 0f, timeMs = 0))
        assertEquals(none, t.tick(1000))
        // The strip starts a threshold right of the key, at 970.
        assertEquals(listOf(by(Drag.SELECT, 13)), t.move(0, 995f, 0f, timeMs = 1000))
        assertEquals(listOf(by(Drag.SELECT, 1)), t.tick(1100))
    }

    @Test
    fun swipingIntoTheEdgeAndHoldingIsNoFlick() {
        val t = edged(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap())
        t.move(0, 445f, 0f, timeMs = 0)
        assertEquals(none, t.move(0, 45f, 0f, timeMs = 50))
        assertEquals(listOf(by(Drag.SCRUB, 150)), t.tick(1050))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 45f, 0f))
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
    fun restingOnALongPressKeyFiresOnceItIsDue() {
        val t = touches()
        t.down(0, o, 0f, 0f, emptyMap(), timeMs = 1000)
        assertTrue(t.ticking)
        assertEquals(none, t.tick(1299))
        t.move(0, 10f, 5f, timeMs = 1200)
        assertEquals(listOf(press(o, Gesture.Hold)), t.tick(1300))
        assertFalse(t.ticking)
        assertEquals(none, t.up(0, 10f, 5f))
    }

    @Test
    fun liftingBeforeTheLongPressIsATap() {
        val t = touches()
        t.down(0, o, 0f, 0f, emptyMap(), timeMs = 1000)
        assertEquals(listOf(press(o)), t.up(0, 0f, 0f))
        assertEquals(none, t.tick(2000))
    }

    @Test
    fun swipingOffALongPressKeyCancelsTheLongPress() {
        val t = touches()
        t.down(0, o, 0f, 0f, emptyMap(), timeMs = 1000)
        t.move(0, 0f, -60f, timeMs = 1100)
        assertEquals(none, t.tick(2000))
        assertEquals(listOf(press(o, Gesture.Swipe(Direction.UP))), t.up(0, 0f, -60f))
    }

    @Test
    fun longPressKeysReadUpRightBetweenUpAndRight() {
        val t = touches()
        t.down(0, o, 0f, 0f, emptyMap())
        // 40° right of straight up.
        assertEquals(none, t.move(0, 64f, -77f, timeMs = 0))
        assertEquals(listOf(press(o, Gesture.Swipe(Direction.UP_RIGHT))), t.up(0, 64f, -77f))
        // 20° is still up, 70° still a rightward drag.
        t.down(1, o, 0f, 0f, emptyMap())
        assertEquals(listOf(press(o, Gesture.Swipe(Direction.UP))), t.up(1, 21f, -56f))
        t.down(2, o, 0f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.RECALL, 1)), t.move(2, 56f, -21f, timeMs = 0))
    }

    @Test
    fun holdingDeleteRepeatsFasterTheLongerItIsHeld() {
        val t = touches()
        t.down(0, del, 0f, 0f, emptyMap(), timeMs = 1000)
        assertTrue(t.ticking)
        assertEquals(none, t.tick(1299))
        assertEquals(listOf(press(del)), t.tick(1300))
        // 100ms in: 11/s.
        assertEquals(listOf(press(del)), t.tick(1400))
        // A second in: 110/s for 0.9s, plus what was owed.
        assertEquals(99, t.tick(2300).size)
        assertEquals(none, t.move(0, -80f, 0f, timeMs = 2300))
        assertEquals(none, t.up(0, -80f, 0f))
    }

    @Test
    fun anotherFingerStopsTheRepeat() {
        val t = touches()
        t.down(0, del, 0f, 0f, emptyMap(), timeMs = 1000)
        t.tick(1300)
        assertEquals(none, t.down(1, a, 100f, 0f, mapOf(0 to Point(0f, 0f)), timeMs = 1350))
        assertEquals(none, t.tick(2000))
    }

    @Test
    fun deleteLiftedBeforeTheDelayIsOneTap() {
        val t = touches()
        t.down(0, del, 0f, 0f, emptyMap(), timeMs = 1000)
        assertEquals(listOf(press(del)), t.up(0, 0f, 0f))
        assertEquals(none, t.tick(2000))
    }

    @Test
    fun dragKeysReadTheDiagonalsTheyDefineAndKeepTheirDrag() {
        val t = touches()
        // 40° left of straight up, 40° right of straight down.
        t.down(0, e, 0f, 0f, emptyMap())
        assertEquals(listOf(press(e, Gesture.Swipe(Direction.UP_LEFT))), t.up(0, -64f, -77f))
        t.down(1, e, 0f, 0f, emptyMap())
        assertEquals(listOf(press(e, Gesture.Swipe(Direction.DOWN_RIGHT))), t.up(1, 64f, 77f))
        // Up-right is not defined here, so 40° right of up is still up.
        t.down(2, e, 0f, 0f, emptyMap())
        assertEquals(listOf(press(e, Gesture.Swipe(Direction.UP))), t.up(2, 64f, -77f))
        // Straight left still scrubs.
        t.down(3, e, 500f, 0f, emptyMap())
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(3, 440f, 0f, timeMs = 0))
    }

    @Test
    fun keysWithoutALongPressNeverTick() {
        val t = touches()
        t.down(0, a, 0f, 0f, emptyMap(), timeMs = 1000)
        assertFalse(t.ticking)
        assertEquals(none, t.tick(5000))
    }

    @Test
    fun coalesceMergesRunsOfTheSameDragAndDropsWhatCancels() {
        val steps = listOf(by(Drag.SCRUB, 1), by(Drag.SCRUB, 2), end(Drag.SCRUB), by(Drag.MOVE, 1), by(Drag.MOVE, -1))
        assertEquals(listOf(by(Drag.SCRUB, 3), end(Drag.SCRUB)), coalesce(steps))
        assertEquals(listOf(press(a), by(Drag.MOVE, 1)), coalesce(listOf(press(a), by(Drag.MOVE, 1))))
    }

    @Test
    fun aSecondFlickSoonAfterGoesByWords() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap(), timeMs = 1000)
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(0, 445f, 0f, timeMs = 1010))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(0, 445f, 0f, timeMs = 1050))
        t.down(1, b, 500f, 0f, emptyMap(), timeMs = 1200)
        assertEquals(listOf(words(Drag.SCRUB, 1)), t.move(1, 445f, 0f, timeMs = 1210))
        assertEquals(listOf(end(Drag.SCRUB)), t.up(1, 445f, 0f, timeMs = 1250))
        // A third keeps the chain going; one much later starts afresh.
        t.down(2, a, 500f, 0f, emptyMap(), timeMs = 1400)
        assertEquals(listOf(words(Drag.SCRUB, 1)), t.move(2, 445f, 0f, timeMs = 1410))
        t.up(2, 445f, 0f, timeMs = 1450)
        t.down(3, a, 500f, 0f, emptyMap(), timeMs = 3000)
        assertEquals(listOf(by(Drag.SCRUB, 1)), t.move(3, 445f, 0f, timeMs = 3010))
    }

    @Test
    fun flicksTooQuickForAMoveChainToo() {
        val t = touches(flickMs = 150)
        t.down(0, moveSpace, 500f, 0f, emptyMap(), timeMs = 1000)
        assertEquals(listOf(by(Drag.MOVE, -1), end(Drag.MOVE)), t.up(0, 430f, 0f, timeMs = 1040))
        t.down(1, moveSpace, 500f, 0f, emptyMap(), timeMs = 1100)
        assertEquals(listOf(words(Drag.MOVE, -1), end(Drag.MOVE)), t.up(1, 430f, 0f, timeMs = 1140))
    }

    @Test
    fun theChainNeedsTheSameDragAndWayWithNothingBetween() {
        val t = touches(flickMs = 150)
        t.down(0, moveSpace, 500f, 0f, emptyMap(), timeMs = 1000)
        t.up(0, 430f, 0f, timeMs = 1040)
        // The other way is no chain.
        t.down(1, moveSpace, 500f, 0f, emptyMap(), timeMs = 1100)
        assertEquals(listOf(by(Drag.MOVE, 1), end(Drag.MOVE)), t.up(1, 570f, 0f, timeMs = 1140))
        // A tap between breaks it.
        t.down(2, a, 0f, 0f, emptyMap(), timeMs = 1200)
        t.up(2, 0f, 0f, timeMs = 1220)
        t.down(3, moveSpace, 500f, 0f, emptyMap(), timeMs = 1250)
        assertEquals(listOf(by(Drag.MOVE, 1), end(Drag.MOVE)), t.up(3, 570f, 0f, timeMs = 1290))
    }

    @Test
    fun recallNeverGoesByWords() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap(), timeMs = 1000)
        t.up(0, 570f, 0f, timeMs = 1040)
        t.down(1, a, 500f, 0f, emptyMap(), timeMs = 1100)
        assertEquals(listOf(by(Drag.RECALL, 1)), t.move(1, 555f, 0f, timeMs = 1110))
    }

    @Test
    fun aWordDragGoesSlowerThanAClusterDrag() {
        val t = touches(flickMs = 150)
        t.down(0, a, 500f, 0f, emptyMap(), timeMs = 1000)
        t.move(0, 445f, 0f, timeMs = 1010)
        t.up(0, 445f, 0f, timeMs = 1050)
        t.down(1, a, 500f, 0f, emptyMap(), timeMs = 1200)
        t.move(1, 445f, 0f, timeMs = 1210)
        // 40px at 100px/s: four clusters, so 1.2 words.
        assertEquals(listOf(words(Drag.SCRUB, 1)), t.move(1, 405f, 0f, timeMs = 1610))
    }

    @Test
    fun coalesceKeepsWordAndClusterStepsApart() {
        assertEquals(
            listOf(by(Drag.MOVE, 2), words(Drag.MOVE, 1)),
            coalesce(listOf(by(Drag.MOVE, 1), by(Drag.MOVE, 1), words(Drag.MOVE, 1))),
        )
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
