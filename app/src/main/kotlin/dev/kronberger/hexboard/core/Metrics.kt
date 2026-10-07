package dev.kronberger.hexboard.core

import kotlin.math.min
import kotlin.math.roundToInt

/** Preferred keyboard height before the screen-fraction cap applies. */
const val KEYBOARD_HEIGHT_DP = 260f

/** Never take more than this share of the screen, e.g. in landscape. */
const val MAX_SCREEN_FRACTION = 0.45f

fun keyboardHeightPx(screenHeightPx: Int, density: Float): Int =
    min(KEYBOARD_HEIGHT_DP * density, screenHeightPx * MAX_SCREEN_FRACTION).roundToInt()
