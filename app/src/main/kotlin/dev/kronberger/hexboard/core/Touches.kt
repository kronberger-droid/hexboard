package dev.kronberger.hexboard.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a finger produced: a key press to resolve, or an action to perform as is. */
sealed interface TouchEvent {
    data class Press(val key: Key, val gesture: Gesture) : TouchEvent
    data class Act(val action: KeyAction) : TouchEvent
}

/** Finger travel per cluster for a slow finger: the finest a drag gets. */
const val DRAG_STEP_DP = 8f

/** Finger speed up to which a drag keeps its finest step. */
const val DRAG_SLOW_DP_S = 250f

/** Finger speed at which a drag reaches [DRAG_GAIN_MAX]. */
const val DRAG_FAST_DP_S = 2500f

/** How many times more clusters a fast finger covers than a slow one, per dp. */
const val DRAG_GAIN_MAX = 8f

/** A drag lifted this soon after it started was a flick and moves one cluster. */
const val DRAG_FLICK_MS = 150L

/** A drag starting slower than this is no flick, so it shows its moves at once. */
const val DRAG_FLICK_DP_S = 300f

/** Time constant of the finger speed estimate, which irons out sample jitter. */
const val DRAG_SMOOTH_MS = 30f

/** Width of the strip at either side of the keyboard where a held drag keeps going. */
const val DRAG_EDGE_DP = 40f

/** Speed of a drag that just reached the edge strip, in clusters per second. */
const val DRAG_EDGE_RATE_START = 10f

/** Speed of a drag held in the edge strip for [DRAG_EDGE_RAMP_MS] or longer. */
const val DRAG_EDGE_RATE_MAX = 150f

/** How long a drag held in the edge strip takes to reach [DRAG_EDGE_RATE_MAX]. */
const val DRAG_EDGE_RAMP_MS = 2000f

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
 * Keeps a drag going once the finger runs out of room: inside the last
 * [zonePx] before the keyboard's edge it moves on by itself, like key
 * repeat. It starts at [startPerS] and speeds up the longer the finger
 * stays, reaching [maxPerS] after [rampMs]. Where in the strip the finger
 * sits does not matter; a fingertip is about as wide as the strip.
 */
class DragEdge(
    val zonePx: Float,
    private val startPerS: Float,
    private val maxPerS: Float,
    private val rampMs: Float,
) {
    /** Clusters per second after [heldMs] in the strip. */
    fun speed(heldMs: Long): Float {
        val t = (heldMs / rampMs).coerceIn(0f, 1f)
        return startPerS + (maxPerS - startPerS) * t * t
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
 * A sideways drag moves one cluster as it starts. While it may still be a
 * flick, moving faster than [flickPxPerS] within [flickMs] of starting,
 * whatever follows is held back and dropped if the finger lifts, so a
 * flick moves exactly one and shows nothing more. A slower start shows
 * its moves at once. From there the drag moves only while the finger does, by [gain] for the finger's speed: slow for fine
 * work, fast to cover distance. Moving back at the same speed undoes the
 * same amount. Where the finger runs out of room, [edge] carries on, which
 * the caller drives with [tick] every frame while [dragging]. A drag is
 * never finished early by another finger, and fingers landing while it
 * runs are ignored.
 */
class Touches(
    private val thresholdPx: Float,
    private val gain: DragGain,
    private val flickMs: Long,
    private val flickPxPerS: Float,
    private val edge: DragEdge,
) {
    /** Horizontal extent of the keyboard the finger can reach, in px. */
    private var left = Float.NEGATIVE_INFINITY
    private var right = Float.POSITIVE_INFINITY

    fun span(left: Float, right: Float) {
        this.left = left
        this.right = right
    }

    private enum class Way { UP, DOWN, LEFT, RIGHT }

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float) {
        /** Set once the finger passes the threshold; it does not change after. */
        var way: Way? = null
        var drag: Drag? = null
        var lastX = x
        var lastMs = 0L
        var startMs = 0L

        /** Latest time seen from moves or ticks. */
        var seenMs = 0L

        /** Up to when the edge has pushed the drag. */
        var pushedMs = 0L

        /** Which edge strip the finger is in, -1 left, 1 right, 0 none, and since when. */
        var edgeSide = 0
        var edgeSinceMs = 0L

        /** Clusters held back while the drag may be a flick. */
        var held = 0

        /** Set once the drag is known not to be a flick; it does not change after. */
        var live = false

        /** Smoothed finger speed in px/s while dragging. */
        var speed = 0f

        /** Clusters moved but not yet reported, within half a cluster of zero. */
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
        t.startMs = timeMs
        t.seenMs = timeMs
        t.pushedMs = timeMs
        t.edgeSide = side(t)
        t.edgeSinceMs = timeMs
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
            t.seenMs = max(t.seenMs, timeMs)
        }
        t.lastX = x
        val side = side(t)
        if (side != t.edgeSide) {
            t.edgeSide = side
            t.edgeSinceMs = timeMs
            t.pushedMs = timeMs
        }
        t.carry += forward(drag, dx) * gain.at(t.speed)
        return report(t, drag)
    }

    /**
     * Bring every drag up to [nowMs]: push those whose finger sits in an
     * edge strip, and release what a drag held back once it is no flick.
     */
    fun tick(nowMs: Long): List<TouchEvent> = active.mapNotNull { t ->
        val drag = t.drag ?: return@mapNotNull null
        t.seenMs = max(t.seenMs, nowMs)
        if (t.edgeSide != 0) {
            val seconds = (nowMs - t.pushedMs) / 1000f
            t.pushedMs = nowMs
            t.carry += forward(drag, t.edgeSide * edge.speed(nowMs - t.edgeSinceMs) * seconds)
        }
        report(t, drag)
    }

    /** [dx] screen pixels rightwards in [drag]'s sign: a scrub grows leftwards. */
    private fun forward(drag: Drag, dx: Float) = if (drag == Drag.SCRUB) -dx else dx

    /**
     * The edge strip [t]'s finger is in: -1 left, 1 right, 0 neither. A
     * strip only counts once the finger is past the threshold towards
     * that edge, so a key that starts inside it does not run off on its own.
     */
    private fun side(t: Touch): Int {
        val rightIn = max(right - edge.zonePx, t.x + thresholdPx)
        if (t.lastX > rightIn && rightIn < right) return 1
        val leftIn = min(left + edge.zonePx, t.x - thresholdPx)
        if (t.lastX < leftIn && leftIn > left) return -1
        return 0
    }

    /** Report the whole clusters of [t]'s carry, unless it may be a flick. */
    private fun report(t: Touch, drag: Drag): TouchEvent? {
        // Rounding keeps the remainder within half a cluster, so jitter
        // smaller than a step never flips the cursor back and forth.
        val whole = t.carry.roundToInt()
        t.carry -= whole
        t.held += whole
        if (flicking(t) || t.held == 0) return null
        val delta = t.held
        t.held = 0
        return TouchEvent.Act(KeyAction.DragBy(drag, delta))
    }

    private fun flicking(t: Touch): Boolean {
        if (t.live) return false
        t.live = t.seenMs - t.startMs >= flickMs || t.speed <= flickPxPerS
        return !t.live
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
            // A flick keeps only its first step. Otherwise land where the
            // finger lifted, even if no move event got there.
            val last = if (flicking(t)) null else glide(t, x, t.lastMs)
            return listOfNotNull(last, TouchEvent.Act(KeyAction.DragEnd(drag)))
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
