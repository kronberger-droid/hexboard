package dev.kronberger.hexboard.core

import kotlin.math.min
import kotlin.math.roundToInt

/** Never take more than this share of the screen, e.g. in landscape. */
const val MAX_SCREEN_FRACTION = 0.45f

/**
 * Height of the key area: regular hexes spanning [widthPx], unless that
 * would exceed the screen-fraction cap, in which case the grid shrinks and
 * sits centered.
 */
fun keyboardHeightPx(layout: Layout, widthPx: Int, screenHeightPx: Int): Int =
    min(widthPx * HexGrid.aspect(layout.fitCells), screenHeightPx * MAX_SCREEN_FRACTION).roundToInt()
