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
 * recall. Which of the four is fixed when the finger first passes the
 * threshold, so an up swipe that drifts left stays up.
 *
 * Scrub and recall are drags measured in grapheme clusters, one per
 * [scrubStepPx] from where the finger went down. A scrub selects clusters
 * before the cursor and deletes them on release; a recall brings deleted
 * clusters back one by one and keeps them on release. Either shrinks as
 * the finger comes back. A drag is never finished early by another finger,
 * and fingers landing while it runs are ignored.
 */
class Touches(private val thresholdPx: Float, private val scrubStepPx: Float) {

    private enum class Way { UP, DOWN, LEFT, RIGHT }

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float) {
        val fourWay get() = key.alternates.isEmpty()

        /** Set once the finger passes the threshold; it does not change after. */
        var way: Way? = null
        val dragging get() = way == Way.LEFT || way == Way.RIGHT
        var steps = 0

        /** Clusters covered by a drag ending [dx] from where the finger went down. */
        fun stepsAt(dx: Float, stepPx: Float) =
            floor((if (way == Way.LEFT) -dx else dx) / stepPx).toInt().coerceAtLeast(0)

        fun preview(steps: Int) = if (way == Way.LEFT) KeyAction.ScrubTo(steps) else KeyAction.RecallTo(steps)
        fun end(steps: Int) = if (way == Way.LEFT) KeyAction.ScrubEnd(steps) else KeyAction.RecallEnd(steps)
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
        val (keep, finish) = active.partition { it.dragging }
        val events = finish.map { t ->
            val p = positions[t.id] ?: Point(t.x, t.y)
            finished(t, p.x, p.y)
        }
        active.retainAll(keep)
        // Typing during a drag would land inside the previewed selection or
        // recall and leave the drag's offsets pointing at changed text.
        if (key != null && keep.isEmpty()) active += Touch(id, key, x, y)
        return events
    }

    /** Finger [id] moved to ([x], [y]). Only scrub and recall drags react before release. */
    fun move(id: Int, x: Float, y: Float): List<TouchEvent> {
        val t = active.find { it.id == id && it.fourWay } ?: return emptyList()
        val dx = x - t.x
        if (t.way == null) t.way = way(dx, y - t.y)
        if (!t.dragging) return emptyList()
        val steps = t.stepsAt(dx, scrubStepPx)
        if (steps == t.steps) return emptyList()
        t.steps = steps
        return listOf(TouchEvent.Act(t.preview(steps)))
    }

    /** Finger [id] lifted at ([x], [y]); empty if it was already finished. */
    fun up(id: Int, x: Float, y: Float): List<TouchEvent> {
        val i = active.indexOfFirst { it.id == id }
        if (i < 0) return emptyList()
        return listOf(finished(active.removeAt(i), x, y))
    }

    /** The gesture was taken away, e.g. by the system; drop everything. */
    fun cancel(): List<TouchEvent> {
        val events = active.filter { it.dragging }.map { TouchEvent.Act(it.end(0)) }
        active.clear()
        return events
    }

    private fun finished(t: Touch, x: Float, y: Float): TouchEvent {
        val dx = x - t.x
        val dy = y - t.y
        if (!t.fourWay) return TouchEvent.Press(t.key, classify(dx, dy, thresholdPx))
        if (t.way == null) t.way = way(dx, dy)
        return when (t.way) {
            null -> TouchEvent.Press(t.key, Gesture.Tap)
            Way.LEFT, Way.RIGHT -> TouchEvent.Act(t.end(t.stepsAt(dx, scrubStepPx)))
            Way.UP -> TouchEvent.Press(t.key, Gesture.Swipe(Direction.UP))
            Way.DOWN -> TouchEvent.Press(t.key, Gesture.Swipe(Direction.DOWN))
        }
    }
}
