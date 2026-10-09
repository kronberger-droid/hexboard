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

    /** A finger rested on the key for [LONG_PRESS_MS] without swiping. */
    data object Hold : Gesture

    /** A long press then a swipe up, or a swipe up then a rest: the long press as a capital. */
    data object HoldUp : Gesture
}

/** Displacement below which a touch counts as a tap. */
const val SWIPE_THRESHOLD_DP = 18f

/** How long a finger rests on a key with a [Key.longPress] before it types that. */
const val LONG_PRESS_MS = 350L

/**
 * How long a finger rests on a key with a [Key.longPress] before the text
 * shows, provisionally, what it would type; longer than a tap takes.
 */
const val PREVIEW_MS = 120L

/** How long a finger rests after swiping up before that types the long press as a capital. */
const val HOLD_UP_MS = 200L

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
