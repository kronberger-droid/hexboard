package dev.kronberger.hexboard.core

import kotlin.math.abs
import kotlin.math.floor

/** What a finger produced: a key press to resolve, or an action to perform as is. */
sealed interface TouchEvent {
    data class Press(val key: Key, val gesture: Gesture) : TouchEvent
    data class Act(val action: KeyAction) : TouchEvent
}

/** Horizontal drag per grapheme cluster while scrubbing on backspace. */
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
 * Backspace is the exception once it scrubs. Dragging it left past the
 * swipe threshold selects one cluster per [scrubStepPx], and fingers that
 * land meanwhile are ignored; release deletes the selection. A
 * swipe right on backspace recalls the last deletion instead.
 */
class Touches(private val thresholdPx: Float, private val scrubStepPx: Float) {

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float) {
        val isDelete get() = key.face.action == KeyAction.Delete
        var scrubbing = false
        var steps = 0
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

    /** Finger [id] moved to ([x], [y]). Only a scrubbing backspace reacts. */
    fun move(id: Int, x: Float, y: Float): List<TouchEvent> {
        val t = active.find { it.id == id && it.isDelete } ?: return emptyList()
        val dx = x - t.x
        if (!t.scrubbing && dx > -thresholdPx) return emptyList()
        t.scrubbing = true
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
        val dx = x - t.x
        val dy = y - t.y
        return when {
            t.scrubbing -> TouchEvent.Act(KeyAction.ScrubEnd(t.steps))
            t.isDelete && dx >= thresholdPx && abs(dx) > abs(dy) -> TouchEvent.Act(KeyAction.Recall)
            t.isDelete -> TouchEvent.Press(t.key, Gesture.Tap)
            else -> TouchEvent.Press(t.key, classify(dx, dy, thresholdPx))
        }
    }
}
