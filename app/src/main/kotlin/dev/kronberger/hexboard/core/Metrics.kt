package dev.kronberger.hexboard.core

import kotlin.math.min
import kotlin.math.roundToInt

/** Vertical distance between neighbouring key rows, like a regular key height. */
const val ROW_PITCH_DP = 56f

/** Never take more than this share of the screen, e.g. in landscape. */
const val MAX_SCREEN_FRACTION = 0.45f

/**
 * Pointy-top rows sit 1.5 radii apart and the outer rows reach one radius
 * past their centers, so [rows] rows span `rows + 1/3` pitches.
 */
fun keyboardHeightPx(rows: Int, screenHeightPx: Int, density: Float): Int =
    min(ROW_PITCH_DP * density * (rows + 1f / 3f), screenHeightPx * MAX_SCREEN_FRACTION).roundToInt()
