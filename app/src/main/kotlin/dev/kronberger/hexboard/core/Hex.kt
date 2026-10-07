package dev.kronberger.hexboard.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Axial coordinates of a pointy-top hex. Rows run along `r`. */
data class Axial(val q: Int, val r: Int) {
    companion object {
        /** Odd rows sit half a hex to the right ("odd-r" offset). */
        fun fromRowCol(row: Int, col: Int) = Axial(col - (row - (row and 1)) / 2, row)
    }
}

data class Point(val x: Float, val y: Float)

private val SQRT3 = sqrt(3f)

/**
 * Pointy-top hex grid mapped onto pixels. The radii differ per axis so a
 * layout can fill the keyboard's height; at `radiusX == radiusY` the hexes
 * are regular.
 */
class HexGrid(
    val radiusX: Float,
    val radiusY: Float,
    val originX: Float,
    val originY: Float,
) {
    fun center(a: Axial) = Point(
        originX + radiusX * SQRT3 * (a.q + a.r / 2f),
        originY + radiusY * 1.5f * a.r,
    )

    /** Six corners clockwise from the top, scaled by [scale] around the center. */
    fun corners(a: Axial, scale: Float = 1f): List<Point> {
        val c = center(a)
        return (0 until 6).map { i ->
            val angle = (-90.0 + 60.0 * i) * PI / 180.0
            Point(
                c.x + radiusX * scale * cos(angle).toFloat(),
                c.y + radiusY * scale * sin(angle).toFloat(),
            )
        }
    }

    /**
     * The cell whose center is nearest to ([x], [y]), measured in units of
     * the radii so the answer matches the drawn, possibly stretched hexes.
     * Points outside every hex still resolve to the closest one.
     */
    fun nearest(cells: Collection<Axial>, x: Float, y: Float): Axial? = cells.minByOrNull { a ->
        val c = center(a)
        val dx = (x - c.x) / radiusX
        val dy = (y - c.y) / radiusY
        dx * dx + dy * dy
    }

    companion object {
        /** The grid that makes [cells] exactly fill the given box. */
        fun fit(cells: Collection<Axial>, left: Float, top: Float, width: Float, height: Float): HexGrid {
            require(cells.isNotEmpty()) { "cannot fit an empty layout" }
            // Extents in units of one radius, including each hex's own reach.
            val xs = cells.map { SQRT3 * (it.q + it.r / 2f) }
            val ys = cells.map { 1.5f * it.r }
            val minX = xs.min() - SQRT3 / 2
            val minY = ys.min() - 1f
            val rx = width / (xs.max() + SQRT3 / 2 - minX)
            val ry = height / (ys.max() + 1f - minY)
            return HexGrid(rx, ry, left - minX * rx, top - minY * ry)
        }
    }
}
