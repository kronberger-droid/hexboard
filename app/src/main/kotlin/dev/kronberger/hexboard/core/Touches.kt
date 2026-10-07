package dev.kronberger.hexboard.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/** What a finger produced: a key press to resolve, or an action to perform as is. */
sealed interface TouchEvent {
    data class Press(val key: Key, val gesture: Gesture) : TouchEvent
    data class Act(val action: KeyAction) : TouchEvent
}

/** Horizontal drag per grapheme cluster while scrubbing. */
const val SCRUB_STEP_DP = 12f

/**
 * Tracks every finger on the keyboard by pointer id and turns them into
 * [TouchEvent]s in touch-down order.
 *
 * A press normally finishes when its finger lifts. When another finger
 * lands first, every finger already down is finished on the spot, judged
 * from where it is now. In rolling typing the earlier key can otherwise
 * be released after the later one and come out second.
 *
 * Keys with swipe alternates read six directions (see [classify]). Every
 * other key reads four: up and down as usual, left to scrub, right to
 * recall the last deletion. Which of the four is fixed when the finger
 * first passes the threshold, so an up swipe that drifts left stays up.
 *
 * A scrub selects one grapheme cluster per [scrubStepPx] dragged left of
 * where the finger went down, shrinks again as it comes back, and deletes
 * the selection on release. It is never finished early by another finger,
 * and fingers landing while it runs are ignored.
 */
class Touches(private val thresholdPx: Float, private val scrubStepPx: Float) {

    private enum class Way { UP, DOWN, LEFT, RIGHT }

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float) {
        val fourWay get() = key.alternates.isEmpty()

        /** Set once the finger passes the threshold; it does not change after. */
        var way: Way? = null
        val scrubbing get() = way == Way.LEFT
        var steps = 0
    }

    /** The four-way direction of a displacement, or null below the threshold. */
    private fun way(dx: Float, dy: Float): Way? = when {
        hypot(dx, dy) < thresholdPx -> null
        abs(dx) > abs(dy) -> if (dx < 0) Way.LEFT else Way.RIGHT
        dy < 0 -> Way.UP
        else -> Way.DOWN
    }

    /** Fingers still down, oldest first. */
    private val active = mutableListOf<Touch>()

    /**
     * Finger [id] went down on [key] at ([x], [y]). [positions] holds the
     * current position of every other finger, used to finish them.
     */
    fun down(id: Int, key: Key?, x: Float, y: Float, positions: Map<Int, Point>): List<TouchEvent> {
        val (keep, finish) = active.partition { it.scrubbing }
        val events = finish.map { t ->
            val p = positions[t.id] ?: Point(t.x, t.y)
            finished(t, p.x, p.y)
        }
        active.retainAll(keep)
        // Typing during a scrub would replace the previewed selection and
        // leave the scrub's offsets pointing at changed text.
        if (key != null && keep.isEmpty()) active += Touch(id, key, x, y)
        return events
    }

    /** Finger [id] moved to ([x], [y]). Only scrubs react before release. */
    fun move(id: Int, x: Float, y: Float): List<TouchEvent> {
        val t = active.find { it.id == id && it.fourWay } ?: return emptyList()
        val dx = x - t.x
        if (t.way == null) t.way = way(dx, y - t.y)
        if (!t.scrubbing) return emptyList()
        val steps = floor(-dx / scrubStepPx).toInt().coerceAtLeast(0)
        if (steps == t.steps) return emptyList()
        t.steps = steps
        return listOf(TouchEvent.Act(KeyAction.ScrubTo(steps)))
    }

    /** Finger [id] lifted at ([x], [y]); empty if it was already finished. */
    fun up(id: Int, x: Float, y: Float): List<TouchEvent> {
        val i = active.indexOfFirst { it.id == id }
        if (i < 0) return emptyList()
        return listOf(finished(active.removeAt(i), x, y))
    }

    /** The gesture was taken away, e.g. by the system; drop everything. */
    fun cancel(): List<TouchEvent> {
        val events = active.filter { it.scrubbing }.map { TouchEvent.Act(KeyAction.ScrubEnd(0)) }
        active.clear()
        return events
    }

    private fun finished(t: Touch, x: Float, y: Float): TouchEvent {
        if (t.scrubbing) return TouchEvent.Act(KeyAction.ScrubEnd(t.steps))
        val dx = x - t.x
        val dy = y - t.y
        if (!t.fourWay) return TouchEvent.Press(t.key, classify(dx, dy, thresholdPx))
        return when (t.way ?: way(dx, dy)) {
            null, Way.LEFT -> TouchEvent.Press(t.key, Gesture.Tap)
            Way.RIGHT -> TouchEvent.Act(KeyAction.Recall)
            Way.UP -> TouchEvent.Press(t.key, Gesture.Swipe(Direction.UP))
            Way.DOWN -> TouchEvent.Press(t.key, Gesture.Swipe(Direction.DOWN))
        }
    }
}
