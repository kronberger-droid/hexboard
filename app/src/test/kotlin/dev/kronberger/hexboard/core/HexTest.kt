package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class HexTest {

    private val sqrt3 = sqrt(3f)
    private val eps = 1e-3f

    @Test
    fun oddRowsShiftHalfAHexRight() {
        val grid = HexGrid(radius = 10f, originX = 0f, originY = 0f)
        val even = grid.center(Axial.fromRowCol(2, 3))
        val odd = grid.center(Axial.fromRowCol(1, 3))
        assertEquals(3 * sqrt3 * 10f, even.x, eps)
        assertEquals(3.5f * sqrt3 * 10f, odd.x, eps)
        assertEquals(15f, odd.y, eps)
    }

    @Test
    fun fitFillsTheLimitingAxisAndCentersTheOther() {
        val cells = Layouts.english.fitCells
        // Too wide for the layout's aspect: height limits, width is centered.
        val grid = HexGrid.fit(cells, left = 0f, top = 10f, width = 2000f, height = 800f)
        val corners = cells.flatMap { grid.corners(it) }
        assertEquals(10f, corners.minOf { it.y }, eps)
        assertEquals(810f, corners.maxOf { it.y }, eps)
        val usedWidth = corners.maxOf { it.x } - corners.minOf { it.x }
        assertEquals((2000f - usedWidth) / 2, corners.minOf { it.x }, eps)
    }

    @Test
    fun centersHitThemselves() {
        val cells = Layouts.english.cells
        val grid = HexGrid.fit(Layouts.english.fitCells, 0f, 0f, 1080f, 713f)
        for (cell in cells) {
            val c = grid.center(cell)
            assertEquals(cell, grid.nearest(cells, c.x, c.y))
        }
    }

    @Test
    fun touchesOutsideTheGridSnapToTheEdgeKey() {
        val cells = Layouts.english.cells
        val grid = HexGrid.fit(Layouts.english.fitCells, 0f, 0f, 1080f, 713f)
        assertEquals(Axial.fromRowCol(1, 0), grid.nearest(cells, -50f, grid.center(Axial.fromRowCol(1, 0)).y))
    }
}
