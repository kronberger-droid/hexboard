package dev.kronberger.hexboard.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** Swipe directions, clockwise from up. Each covers a 60° sector. */
enum class Direction { UP, UP_RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT, UP_LEFT }

sealed interface Gesture {
    data object Tap : Gesture
    data class Swipe(val direction: Direction) : Gesture
}

/** Displacement below which a touch counts as a tap. */
const val SWIPE_THRESHOLD_DP = 18f

/**
 * Classify a touch by its displacement from touch down to release, in
 * screen pixels (y grows downwards). The sectors are centered on up and
 * down so the most common swipe, up for a capital, sits mid-sector.
 */
fun classify(dx: Float, dy: Float, thresholdPx: Float): Gesture {
    if (hypot(dx, dy) < thresholdPx) return Gesture.Tap
    // Degrees clockwise from straight up, in [0, 360).
    val fromUp = (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI + 360.0) % 360.0
    val sector = floor((fromUp + 30.0) / 60.0).toInt() % 6
    return Gesture.Swipe(Direction.entries[sector])
}
