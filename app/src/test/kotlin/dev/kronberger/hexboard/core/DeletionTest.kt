package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun recallReturnsTheLastDeletionOnce() {
        val r = Recall()
        r.record("x", cursorBefore = 5, cursorAfter = 4)
        assertEquals("x", r.take())
        assertNull(r.take())
    }

    @Test
    fun deletionsInARowPileUpInTextOrder() {
        val r = Recall()
        r.record("c", 3, 2)
        r.record("b", 2, 1)
        assertEquals("bc", r.take())
    }

    @Test
    fun deletionElsewhereStartsANewRun() {
        val r = Recall()
        r.record("c", 3, 2)
        r.record("z", 9, 8)
        assertEquals("z", r.take())
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
}
