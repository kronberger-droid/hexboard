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

/** Finger travel past the swipe threshold per cluster, while the drag follows the finger. */
const val DRAG_STEP_DP = 8f

/** Clusters a drag covers by finger position before speed takes over. */
const val DRAG_FINE_STEPS = 6

/** Distance beyond the fine steps over which speed climbs to [DRAG_RATE_MAX]. */
const val DRAG_RAMP_DP = 120f

/** Fastest a drag goes, in clusters per second. */
const val DRAG_RATE_MAX = 400f

/**
 * How far a sideways drag goes for a finger some distance past the swipe
 * threshold. Close in, the drag follows the finger: one cluster at the
 * threshold and one more every [stepPx], up to [fineSteps]. Past those,
 * speed starts at zero and climbs with the square of the distance to
 * [maxPerSecond] over [rampPx], so going slightly beyond creeps and going
 * far races.
 */
class DragCurve(
    private val stepPx: Float,
    private val fineSteps: Int,
    private val rampPx: Float,
    private val maxPerSecond: Float,
) {
    /** Clusters the finger's position stands for, [pastPx] beyond the threshold. */
    fun steps(pastPx: Float): Int = min(1 + (pastPx / stepPx).toInt(), fineSteps)

    /** Clusters per second on top of [steps], [pastPx] beyond the threshold. */
    fun speed(pastPx: Float): Float {
        val ramp = ((pastPx - fineSteps * stepPx) / rampPx).coerceIn(0f, 1f)
        return maxPerSecond * ramp * ramp
    }
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
 * exactly one. From there [curve] sets how far it goes: by the finger's
 * position near the start, then at a speed that grows with its distance
 * from where it went down. Coming back undoes the position steps, and
 * crossing the start reverses. The caller drives this with [tick] every
 * frame while [dragging]. A drag is never finished early by another
 * finger, and fingers landing while it runs are ignored.
 */
class Touches(private val thresholdPx: Float, private val curve: DragCurve) {

    private enum class Way { UP, DOWN, LEFT, RIGHT }

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float) {
        /** Set once the finger passes the threshold; it does not change after. */
        var way: Way? = null
        var drag: Drag? = null
        var lastX = x
        var lastMs = 0L

        /** Clusters covered by speed so far, signed like the drag. */
        var sped = 0f

        /** Clusters reported so far, signed like the drag. */
        var reported = 0
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
        t.reported = firstStep(drag, way)
        return listOf(TouchEvent.Act(KeyAction.DragBy(drag, t.reported)))
    }

    /** Advance every drag to [nowMs] by where its finger is now. */
    fun tick(nowMs: Long): List<TouchEvent> = active.mapNotNull { t ->
        val seconds = (nowMs - t.lastMs) / 1000f
        t.lastMs = nowMs
        advance(t, seconds)
    }

    /** Report how far [t]'s drag has gone since last time, [seconds] later. */
    private fun advance(t: Touch, seconds: Float): TouchEvent? {
        val drag = t.drag ?: return null
        // A scrub grows leftwards; every other drag counts rightwards.
        val offset = if (drag == Drag.SCRUB) t.x - t.lastX else t.lastX - t.x
        val past = abs(offset) - thresholdPx
        val steps = if (past < 0) 0 else sign(offset).toInt() * curve.steps(past)
        if (past > 0) t.sped += sign(offset) * curve.speed(past) * seconds
        val delta = steps + t.sped.toInt() - t.reported
        if (delta == 0) return null
        t.reported += delta
        return TouchEvent.Act(KeyAction.DragBy(drag, delta))
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
        t.drag?.let { drag ->
            // Land where the finger lifted, even if no frame saw it get there.
            t.lastX = x
            return listOfNotNull(advance(t, 0f), TouchEvent.Act(KeyAction.DragEnd(drag)))
        }
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
