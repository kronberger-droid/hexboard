package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionTest {

    // "hi👨‍👩‍👧🇦🇹": h, i, the family (8 chars), the flag (4 chars).
    private val boundaries = listOf(0, 1, 2, 10, 14)

    @Test
    fun oneStepIsTheWholeLastCluster() {
        assertEquals(10, clusterStart(boundaries, 1))
    }

    @Test
    fun stepsWalkBackClusterByCluster() {
        assertEquals(2, clusterStart(boundaries, 2))
        assertEquals(1, clusterStart(boundaries, 3))
    }

    @Test
    fun zeroStepsIsTheEnd() {
        assertEquals(14, clusterStart(boundaries, 0))
    }

    @Test
    fun stepsPastTheStartStopThere() {
        assertEquals(0, clusterStart(boundaries, 99))
        assertEquals(0, clusterStart(listOf(0), 1))
    }

    @Test
    fun clusterEndMirrorsClusterStart() {
        assertEquals(0, clusterEnd(boundaries, 0))
        assertEquals(1, clusterEnd(boundaries, 1))
        assertEquals(10, clusterEnd(boundaries, 3))
        assertEquals(14, clusterEnd(boundaries, 99))
    }

    @Test
    fun stepClustersMovesWholeClustersEitherWay() {
        assertEquals(10, stepClusters(boundaries, 2, 1))
        assertEquals(14, stepClusters(boundaries, 2, 2))
        assertEquals(1, stepClusters(boundaries, 10, -2))
    }

    @Test
    fun stepClustersStopsAtTheEnds() {
        assertEquals(0, stepClusters(boundaries, 1, -5))
        assertEquals(14, stepClusters(boundaries, 10, 5))
    }

    @Test
    fun stepClustersFromInsideAClusterLandsOnItsEdge() {
        // Offset 5 is inside the family emoji, which spans 2..10.
        assertEquals(10, stepClusters(boundaries, 5, 1))
        assertEquals(2, stepClusters(boundaries, 5, -1))
    }

    @Test
    fun deletionsInARowPileUpInTextOrder() {
        val r = Recall()
        r.record("c", 3, 2)
        r.record("b", 2, 1)
        assertEquals("bc", r.run)
    }

    @Test
    fun deletionElsewhereStartsANewRun() {
        val r = Recall()
        r.record("c", 3, 2)
        r.record("z", 9, 8)
        assertEquals("z", r.run)
    }

    @Test
    fun partialRestoreLeavesTheRestRecallable() {
        val r = Recall()
        r.record("abc", 5, 2)
        r.restored(1, cursorAfter = 3)
        assertEquals("bc", r.run)
        // Deleting right there again piles onto what is left.
        r.record("a", 3, 2)
        assertEquals("abc", r.run)
    }

    @Test
    fun fullRestoreEmptiesTheRun() {
        val r = Recall()
        r.record("ab", 5, 3)
        r.restored(2, cursorAfter = 5)
        assertEquals("", r.run)
    }

    @Test
    fun lateReportsOfOurOwnEditsAreNotOutsideMoves() {
        val c = Cursor()
        c.reset(5, 5)
        c.movedBySelf(4)
        c.movedBySelf(3)
        assertFalse(c.reported(4, 4))
        // The stale report does not drag our view back.
        assertEquals(3, c.start)
        assertFalse(c.reported(3, 3))
        assertEquals(3, c.start)
    }

    @Test
    fun anyOtherReportIsAnOutsideMove() {
        val c = Cursor()
        c.reset(5, 5)
        c.movedBySelf(4)
        assertTrue(c.reported(9, 9))
        assertEquals(9, c.start)
        // Our pending position is forgotten once somebody else moved.
        assertTrue(c.reported(4, 4))
    }

    @Test
    fun aLateReportOfOurPreviewSelectionIsOurs() {
        // A scrub previews 2..5, then deletes it and leaves the cursor at 2;
        // the preview's report arrives after the deletion.
        val c = Cursor()
        c.reset(5, 5)
        c.movedBySelf(2, 5)
        c.movedBySelf(2)
        assertFalse(c.reported(2, 5))
        assertEquals(2, c.end)
    }

    @Test
    fun anUnknownSelectionReportIsTakenAsIs() {
        val c = Cursor()
        c.reset(5, 5)
        c.movedBySelf(4)
        assertTrue(c.reported(2, 4))
        assertEquals(2, c.start)
        assertEquals(4, c.end)
    }

    // "foo bar, baz": words start at 0 4 9 and end at 3 7 12.
    private val starts = listOf(0, 4, 9, 12)
    private val ends = listOf(0, 3, 7, 12)

    @Test
    fun wordStepsLandOnStartsLeftwardsAndEndsRightwards() {
        assertEquals(9, stepWords(starts, ends, 12, -1))
        assertEquals(9, stepWords(starts, ends, 10, -1))
        assertEquals(4, stepWords(starts, ends, 12, -2))
        assertEquals(3, stepWords(starts, ends, 0, 1))
        assertEquals(7, stepWords(starts, ends, 5, 1))
        assertEquals(0, stepWords(starts, ends, 2, -5))
        assertEquals(12, stepWords(starts, ends, 8, 5))
    }
}
