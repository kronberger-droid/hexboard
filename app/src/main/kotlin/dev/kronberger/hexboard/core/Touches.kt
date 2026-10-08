package dev.kronberger.hexboard.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What a finger produced: a key press to resolve, or an action to perform as is. */
sealed interface TouchEvent {
    data class Press(val key: Key, val gesture: Gesture) : TouchEvent
    data class Act(val action: KeyAction) : TouchEvent
}

/** Finger travel per cluster for a slow finger: the finest a drag gets. */
const val DRAG_STEP_DP = 7f

/** Finger speed up to which a drag keeps its finest step. */
const val DRAG_SLOW_DP_S = 150f

/** Finger speed at which a drag reaches [DRAG_GAIN_MAX]. */
const val DRAG_FAST_DP_S = 1500f

/** How many times more clusters a fast finger covers than a slow one, per dp. */
const val DRAG_GAIN_MAX = 16f

/** Time constant of the finger speed estimate, which irons out sample jitter. */
const val DRAG_SMOOTH_MS = 30f

/**
 * How far a sideways drag goes per pixel of finger travel, like pointer
 * acceleration on a trackpad. A finger slower than [slowPxPerS] moves one
 * cluster every [stepPx]; from there the gain eases up to [maxGain] times
 * that at [fastPxPerS]. Finger speed is smoothed over about [smoothMs].
 */
class DragGain(
    private val stepPx: Float,
    private val slowPxPerS: Float,
    private val fastPxPerS: Float,
    private val maxGain: Float,
    val smoothMs: Float,
) {
    /** Clusters per pixel for a finger moving at [pxPerS]. */
    fun at(pxPerS: Float): Float {
        val t = ((pxPerS - slowPxPerS) / (fastPxPerS - slowPxPerS)).coerceIn(0f, 1f)
        val eased = t * t * (3 - 2 * t)
        return (1 + (maxGain - 1) * eased) / stepPx
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
 * exactly one. From there it moves only while the finger does, by [gain]
 * for the finger's speed: slow for fine work, fast to cover distance.
 * Moving back at the same speed undoes the same amount. A drag is never
 * finished early by another finger, and fingers landing while it runs are
 * ignored.
 */
class Touches(private val thresholdPx: Float, private val gain: DragGain) {

    private enum class Way { UP, DOWN, LEFT, RIGHT }

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float) {
        /** Set once the finger passes the threshold; it does not change after. */
        var way: Way? = null
        var drag: Drag? = null
        var lastX = x
        var lastMs = 0L

        /** Smoothed finger speed in px/s while dragging. */
        var speed = 0f

        /** Clusters moved but not yet reported, within half a cluster of zero. */
        var carry = 0f
    }

    /** Fingers still down, oldest first. */
    private val active = mutableListOf<Touch>()

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

    /** Finger [id] moved to ([x], [y]) at [timeMs]. Starts or advances a drag. */
    fun move(id: Int, x: Float, y: Float, timeMs: Long): List<TouchEvent> {
        val t = active.find { it.id == id } ?: return emptyList()
        if (t.drag != null) return listOfNotNull(glide(t, x, timeMs))
        val sideways = t.key.sideways ?: return emptyList()
        if (t.way != null) return emptyList()
        val way = way(x - t.x, y - t.y) ?: return emptyList()
        t.way = way
        if (way == Way.UP || way == Way.DOWN) return emptyList()
        val drag = dragFor(sideways, way)
        t.drag = drag
        t.lastX = x
        t.lastMs = timeMs
        return listOf(TouchEvent.Act(KeyAction.DragBy(drag, firstStep(drag, way))))
    }

    /** Move [t]'s drag along with its finger, now at [x] at [timeMs]. */
    private fun glide(t: Touch, x: Float, timeMs: Long): TouchEvent? {
        val drag = t.drag ?: return null
        val dx = x - t.lastX
        val ms = timeMs - t.lastMs
        if (ms > 0) {
            val follow = if (gain.smoothMs > 0) 1 - exp(-ms / gain.smoothMs) else 1f
            t.speed += (abs(dx) * 1000f / ms - t.speed) * follow
            t.lastMs = timeMs
        }
        t.lastX = x
        // A scrub grows leftwards; every other drag counts rightwards.
        t.carry += (if (drag == Drag.SCRUB) -dx else dx) * gain.at(t.speed)
        // Rounding keeps the remainder within half a cluster, so jitter
        // smaller than a step never flips the cursor back and forth.
        val whole = t.carry.roundToInt()
        if (whole == 0) return null
        t.carry -= whole
        return TouchEvent.Act(KeyAction.DragBy(drag, whole))
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
            // Land where the finger lifted, even if no move event got there.
            return listOfNotNull(glide(t, x, t.lastMs), TouchEvent.Act(KeyAction.DragEnd(drag)))
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
