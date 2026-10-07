package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class HexTest {

    private val sqrt3 = sqrt(3f)
    private val eps = 1e-3f

    @Test
    fun oddRowsShiftHalfAHexRight() {
        val grid = HexGrid(radiusX = 10f, radiusY = 10f, originX = 0f, originY = 0f)
        val even = grid.center(Axial.fromRowCol(2, 3))
        val odd = grid.center(Axial.fromRowCol(1, 3))
        assertEquals(3 * sqrt3 * 10f, even.x, eps)
        assertEquals(3.5f * sqrt3 * 10f, odd.x, eps)
        assertEquals(15f, odd.y, eps)
    }

    @Test
    fun fitFillsTheBoxExactly() {
        val cells = Layouts.english.cells
        val grid = HexGrid.fit(cells, left = 5f, top = 7f, width = 1050f, height = 540f)
        val corners = cells.flatMap { grid.corners(it) }
        assertEquals(5f, corners.minOf { it.x }, eps)
        assertEquals(1055f, corners.maxOf { it.x }, eps)
        assertEquals(7f, corners.minOf { it.y }, eps)
        assertEquals(547f, corners.maxOf { it.y }, eps)
    }

    @Test
    fun centersHitThemselves() {
        val cells = Layouts.english.cells
        val grid = HexGrid.fit(cells, 0f, 0f, 1080f, 560f)
        for (cell in cells) {
            val c = grid.center(cell)
            assertEquals(cell, grid.nearest(cells, c.x, c.y))
        }
    }

    @Test
    fun pointJustInsideAStretchedHexHitsIt() {
        // Tall hexes: a point 0.9 radii above a center is still inside that
        // hex, though in raw pixels it is closer to the row above's centers.
        val cells = listOf(Axial(0, 0), Axial(1, 0), Axial(0, 1))
        val grid = HexGrid(radiusX = 10f, radiusY = 40f, originX = 0f, originY = 0f)
        val lower = grid.center(Axial(0, 1))
        assertEquals(Axial(0, 1), grid.nearest(cells, lower.x, lower.y - 0.9f * 40f))
    }

    @Test
    fun touchesOutsideTheGridSnapToTheEdgeKey() {
        val cells = Layouts.english.cells
        val grid = HexGrid.fit(cells, 0f, 0f, 1080f, 560f)
        assertEquals(Axial.fromRowCol(0, 0), grid.nearest(cells, -50f, -50f))
    }
}
