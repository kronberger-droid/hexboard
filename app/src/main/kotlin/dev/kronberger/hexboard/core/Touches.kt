package dev.kronberger.hexboard.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign

/** What a finger produced: a key press to resolve, or an action to perform as is. */
sealed interface TouchEvent {
    data class Press(val key: Key, val gesture: Gesture) : TouchEvent
    data class Act(val action: KeyAction) : TouchEvent
}

/** Offset from the touch-down point below which a drag stands still. */
const val DRAG_DEAD_DP = 12f

/** Drag speed in clusters per second for every dp beyond [DRAG_DEAD_DP]. */
const val DRAG_RATE_PER_DP = 0.4f

/** Fastest a drag goes, in clusters per second. */
const val DRAG_RATE_MAX = 60f

/** Speed of a drag held at some offset from where the finger went down. */
class DragRate(private val deadPx: Float, private val perPxPerSecond: Float, private val maxPerSecond: Float) {
    /** Signed clusters per second for a finger [offsetPx] right of its start. */
    fun at(offsetPx: Float): Float =
        sign(offsetPx) * min((abs(offsetPx) - deadPx).coerceAtLeast(0f) * perPxPerSecond, maxPerSecond)
}

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
 * other key reads four: up and down as usual, left and right as given by
 * its [Sideways] drag. Which of the four is fixed when the finger first
 * passes the threshold, so an up swipe that drifts left stays up.
 *
 * A sideways drag moves one cluster as it starts, so a quick flick moves
 * exactly one. While the finger stays down it keeps going at a speed set
 * by [rate] from how far the finger is from where it went down, and
 * reverses when the finger crosses back past that point. The caller drives
 * this with [tick] every frame while [dragging]. A drag is never finished
 * early by another finger, and fingers landing while it runs are ignored.
 */
class Touches(private val thresholdPx: Float, private val rate: DragRate) {

    private enum class Way { UP, DOWN, LEFT, RIGHT }

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float) {
        /** Set once the finger passes the threshold; it does not change after. */
        var way: Way? = null
        var drag: Drag? = null
        var lastX = x
        var lastMs = 0L

        /** Fractional clusters moved but not yet reported. */
        var carry = 0f
    }

    /** Fingers still down, oldest first. */
    private val active = mutableListOf<Touch>()

    val dragging get() = active.any { it.drag != null }

    /** The four-way direction of a displacement, or null below the threshold. */
    private fun way(dx: Float, dy: Float): Way? = when {
        hypot(dx, dy) < thresholdPx -> null
        abs(dx) > abs(dy) -> if (dx < 0) Way.LEFT else Way.RIGHT
        dy < 0 -> Way.UP
        else -> Way.DOWN
    }

    private fun dragFor(sideways: Sideways, way: Way): Drag = when (sideways) {
        Sideways.EDIT -> if (way == Way.LEFT) Drag.SCRUB else Drag.RECALL
        Sideways.MOVE -> Drag.MOVE
        Sideways.SELECT -> Drag.SELECT
    }

    /** The cluster a drag moves as it starts, in that drag's sign convention. */
    private fun firstStep(drag: Drag, way: Way): Int = when (drag) {
        Drag.SCRUB, Drag.RECALL -> 1
        Drag.MOVE, Drag.SELECT -> if (way == Way.LEFT) -1 else 1
    }

    /**
     * Finger [id] went down on [key] at ([x], [y]). [positions] holds the
     * current position of every other finger, used to finish them.
     */
    fun down(id: Int, key: Key?, x: Float, y: Float, positions: Map<Int, Point>): List<TouchEvent> {
        val (keep, finish) = active.partition { it.drag != null }
        val events = finish.flatMap { t ->
            val p = positions[t.id] ?: Point(t.x, t.y)
            finished(t, p.x, p.y)
        }
        active.retainAll(keep)
        // Typing during a drag would land inside its selection or recall
        // and leave the drag's offsets pointing at changed text.
        if (key != null && keep.isEmpty()) active += Touch(id, key, x, y)
        return events
    }

    /** Finger [id] moved to ([x], [y]) at [timeMs]. Only starting a drag reacts here. */
    fun move(id: Int, x: Float, y: Float, timeMs: Long): List<TouchEvent> {
        val t = active.find { it.id == id } ?: return emptyList()
        t.lastX = x
        val sideways = t.key.sideways ?: return emptyList()
        if (t.way != null) return emptyList()
        val way = way(x - t.x, y - t.y) ?: return emptyList()
        t.way = way
        if (way == Way.UP || way == Way.DOWN) return emptyList()
        val drag = dragFor(sideways, way)
        t.drag = drag
        t.lastMs = timeMs
        return listOf(TouchEvent.Act(KeyAction.DragBy(drag, firstStep(drag, way))))
    }

    /** Advance every drag to [nowMs] at the speed its finger's offset asks for. */
    fun tick(nowMs: Long): List<TouchEvent> = active.mapNotNull { t ->
        val drag = t.drag ?: return@mapNotNull null
        val seconds = (nowMs - t.lastMs) / 1000f
        t.lastMs = nowMs
        val offset = t.lastX - t.x
        // A scrub grows leftwards; every other drag counts rightwards.
        t.carry += rate.at(if (drag == Drag.SCRUB) -offset else offset) * seconds
        val whole = t.carry.toInt()
        if (whole == 0) return@mapNotNull null
        t.carry -= whole
        TouchEvent.Act(KeyAction.DragBy(drag, whole))
    }

    /** Finger [id] lifted at ([x], [y]); empty if it was already finished. */
    fun up(id: Int, x: Float, y: Float): List<TouchEvent> {
        val i = active.indexOfFirst { it.id == id }
        if (i < 0) return emptyList()
        return finished(active.removeAt(i), x, y)
    }

    /** The gesture was taken away, e.g. by the system; drop everything. */
    fun cancel(): List<TouchEvent> {
        val events = active.mapNotNull { t -> t.drag?.let { TouchEvent.Act(KeyAction.DragEnd(it, keep = false)) } }
        active.clear()
        return events
    }

    private fun finished(t: Touch, x: Float, y: Float): List<TouchEvent> {
        t.drag?.let { return listOf(TouchEvent.Act(KeyAction.DragEnd(it))) }
        val dx = x - t.x
        val dy = y - t.y
        val sideways = t.key.sideways ?: return listOf(TouchEvent.Press(t.key, classify(dx, dy, thresholdPx)))
        return when (val way = t.way ?: way(dx, dy)) {
            null -> listOf(TouchEvent.Press(t.key, Gesture.Tap))
            Way.UP -> listOf(TouchEvent.Press(t.key, Gesture.Swipe(Direction.UP)))
            Way.DOWN -> listOf(TouchEvent.Press(t.key, Gesture.Swipe(Direction.DOWN)))
            // A flick too quick for any move event: one step, then done.
            Way.LEFT, Way.RIGHT -> {
                val drag = dragFor(sideways, way)
                listOf(
                    TouchEvent.Act(KeyAction.DragBy(drag, firstStep(drag, way))),
                    TouchEvent.Act(KeyAction.DragEnd(drag)),
                )
            }
        }
    }
}
