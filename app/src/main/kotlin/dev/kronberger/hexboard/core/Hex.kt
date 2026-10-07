package dev.kronberger.hexboard.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
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

/** Regular pointy-top hex grid mapped onto pixels. */
class HexGrid(val radius: Float, val originX: Float, val originY: Float) {

    fun center(a: Axial) = Point(
        originX + radius * SQRT3 * (a.q + a.r / 2f),
        originY + radius * 1.5f * a.r,
    )

    /** Six corners clockwise from the top, scaled by [scale] around the center. */
    fun corners(a: Axial, scale: Float = 1f): List<Point> {
        val c = center(a)
        return (0 until 6).map { i ->
            val angle = (-90.0 + 60.0 * i) * PI / 180.0
            Point(
                c.x + radius * scale * cos(angle).toFloat(),
                c.y + radius * scale * sin(angle).toFloat(),
            )
        }
    }

    /**
     * The cell whose center is nearest to ([x], [y]). Inside the grid that is
     * the hex containing the point; outside it, the closest edge hex.
     */
    fun nearest(cells: Collection<Axial>, x: Float, y: Float): Axial? = cells.minByOrNull { a ->
        val c = center(a)
        val dx = x - c.x
        val dy = y - c.y
        dx * dx + dy * dy
    }

    companion object {
        private class Bounds(val minX: Float, val minY: Float, val width: Float, val height: Float)

        /** Extents in units of one radius, including each hex's own reach. */
        private fun bounds(cells: Collection<Axial>): Bounds {
            require(cells.isNotEmpty()) { "cannot fit an empty layout" }
            val xs = cells.map { SQRT3 * (it.q + it.r / 2f) }
            val ys = cells.map { 1.5f * it.r }
            val minX = xs.min() - SQRT3 / 2
            val minY = ys.min() - 1f
            return Bounds(minX, minY, xs.max() + SQRT3 / 2 - minX, ys.max() + 1f - minY)
        }

        /** Height over width of the area [cells] cover. */
        fun aspect(cells: Collection<Axial>): Float = bounds(cells).let { it.height / it.width }

        /** The largest grid that shows all of [cells] inside the box, centered in it. */
        fun fit(cells: Collection<Axial>, left: Float, top: Float, width: Float, height: Float): HexGrid {
            val b = bounds(cells)
            val r = min(width / b.width, height / b.height)
            return HexGrid(
                r,
                left + (width - b.width * r) / 2 - b.minX * r,
                top + (height - b.height * r) / 2 - b.minY * r,
            )
        }
    }
}
