package dev.kronberger.hexboard.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a finger produced: a key press to resolve, or an action to perform as is. */
sealed interface TouchEvent {
    data class Press(val key: Key, val gesture: Gesture) : TouchEvent
    data class Act(val action: KeyAction) : TouchEvent

    /** A long press on [key] is due; what it types is decided when the finger lifts or swipes up. */
    data class Armed(val key: Key) : TouchEvent
}

/**
 * [events] with each run of steps of the same drag merged into one, so the
 * editor gets one change per batch of touch samples instead of one each.
 * Steps that cancel out vanish.
 */
fun coalesce(events: List<TouchEvent>): List<TouchEvent> {
    val out = mutableListOf<TouchEvent>()
    var pending: KeyAction.DragBy? = null
    fun flush() {
        pending?.takeIf { it.delta != 0 }?.let { out += TouchEvent.Act(it) }
        pending = null
    }
    for (e in events) {
        val step = (e as? TouchEvent.Act)?.action as? KeyAction.DragBy
        val p = pending
        when {
            step == null -> {
                flush()
                out += e
            }
            p != null && p.drag == step.drag && p.words == step.words -> pending = p.copy(delta = p.delta + step.delta)
            else -> {
                flush()
                pending = step
            }
        }
    }
    flush()
    return out
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

/** While a drag may still be a flick, a finger slower than this shows its moves at once. */
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
class DragEdge(val zonePx: Float, startPerS: Float, maxPerS: Float, rampMs: Float) {
    private val ramp = Ramp(startPerS, maxPerS, rampMs)

    /** Clusters per second after [heldMs] in the strip. */
    fun speed(heldMs: Long): Float = ramp.speed(heldMs)
}

/** A rate that starts at [startPerS] and eases up to [maxPerS] over [rampMs], like key repeat. */
class Ramp(private val startPerS: Float, private val maxPerS: Float, private val rampMs: Float) {
    /** Per second, [heldMs] after it started. */
    fun speed(heldMs: Long): Float {
        val t = (heldMs / rampMs).coerceIn(0f, 1f)
        return startPerS + (maxPerS - startPerS) * t * t
    }
}

/** A drag starting this soon after a flick of the same kind and way goes by words. */
const val WORD_CHAIN_MS = 400L

/** Words a word drag moves per cluster an ordinary drag would. */
const val WORD_STEP = 0.3f

/** Deletes per second as a held delete key starts repeating. */
const val REPEAT_RATE_START = 10f

/** Deletes per second once a delete key has been held for [REPEAT_RAMP_MS]. */
const val REPEAT_RATE_MAX = 40f

/** How long a held delete key takes to reach [REPEAT_RATE_MAX]. */
const val REPEAT_RAMP_MS = 2000f

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
 * A sideways drag moves one cluster as it starts. A drag lifted within
 * [flickMs] was a flick and keeps only that one. Until then, moves made
 * faster than [flickPxPerS] are held back, so a flick shows nothing more,
 * while slower ones show at once and are taken back if it was a flick
 * after all. From there the drag moves only while the finger does, by
 * [gain] for the finger's speed: slow for fine work, fast to cover
 * distance. Moving back at the same speed undoes the
 * same amount. Where the finger runs out of room, [edge] carries on, which
 * the caller drives with [tick] every frame while [dragging]. A drag is
 * never finished early by another finger, and fingers landing while it
 * runs are ignored.
 *
 * A finger resting on a key with a [Key.longPress] for [holdMs], without
 * passing the threshold, arms it from [tick] ([TouchEvent.Armed]): swiping
 * up from there finishes it with [Gesture.HoldUp], lifting with
 * [Gesture.Hold]. One that swiped up on such a key and then rests, moving
 * less than half the threshold, for [holdUpMs] is finished with
 * [Gesture.HoldUp] from [tick]; lifting it later does nothing more. One resting as long on a key
 * that [Key.repeats] starts tapping it from [tick], at the [repeat] rate,
 * until it lifts or another finger lands.
 *
 * A drag that starts within [WORD_CHAIN_MS] of a flick of the same kind
 * and direction goes by words, at [WORD_STEP] of the usual rate: a second
 * flick moves a word, and flicking then dragging moves word by word.
 */
class Touches(
    private val thresholdPx: Float,
    private val gain: DragGain,
    private val flickMs: Long,
    private val flickPxPerS: Float,
    private val edge: DragEdge,
    private val holdMs: Long,
    private val repeat: Ramp,
    private val holdUpMs: Long,
) {
    /** Horizontal extent of the keyboard the finger can reach, in px. */
    private var left = Float.NEGATIVE_INFINITY
    private var right = Float.POSITIVE_INFINITY

    fun span(left: Float, right: Float) {
        this.left = left
        this.right = right
    }

    private enum class Way(val direction: Direction?) {
        UP(Direction.UP),
        UP_RIGHT(Direction.UP_RIGHT),
        DOWN_RIGHT(Direction.DOWN_RIGHT),
        DOWN(Direction.DOWN),
        DOWN_LEFT(Direction.DOWN_LEFT),
        UP_LEFT(Direction.UP_LEFT),
        LEFT(null),
        RIGHT(null),
    }

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float, val downMs: Long) {
        /** Set once the finger passes the threshold; it does not change after. */
        var way: Way? = null
        var drag: Drag? = null
        var lastX = x
        var lastY = y

        /** A long press is due, armed with the finger at [armY]. */
        var armed = false
        var armY = 0f
        var lastMs = 0L
        var startMs = 0L

        /** Latest time seen from moves or ticks. */
        var seenMs = 0L

        /** Up to when the edge has pushed the drag. */
        var pushedMs = 0L

        /** Which edge strip the finger is in, -1 left, 1 right, 0 none, and since when. */
        var edgeSide = 0
        var edgeSinceMs = 0L

        /** Whether [lastX] and [lastMs] hold a real sample yet. */
        var sampled = false

        /** Speed of the latest sample alone, in px/s. */
        var raw = 0f

        /** Clusters held back while the drag may be a flick. */
        var held = 0

        /** Clusters shown while the drag may be a flick, taken back if it is one. */
        var shown = 0

        /** Smoothed finger speed in px/s while dragging. */
        var speed = 0f

        /** Clusters moved but not yet reported, within half a cluster of zero. */
        var carry = 0f

        /** Where and since when a finger that swiped up has rested. */
        var restX = 0f
        var restY = 0f
        var restMs = 0L

        /** The drag's first step, and whether it goes by words. */
        var step = 0
        var words = false

        /** Whether the key repeats by now, when it last did, and taps owed. */
        var repeating = false
        var repeatedMs = 0L
        var owed = 0f
    }

    /** Fingers still down, oldest first. */
    private val active = mutableListOf<Touch>()

    /** The last drag that ended as a flick: its kind, its first step, and when it ended. */
    private class Flick(val drag: Drag, val step: Int, val endMs: Long)

    private var lastFlick: Flick? = null

    /** Whether a drag of [drag] whose first step is [step], starting at [startMs], chains on the last flick. */
    private fun chained(drag: Drag, step: Int, startMs: Long): Boolean {
        // Recall brings back what was deleted cluster by cluster; words would only slow it.
        val f = lastFlick?.takeIf { drag != Drag.RECALL } ?: return false
        return f.drag == drag && f.step == step && startMs - f.endMs in 0..WORD_CHAIN_MS
    }

    /** No finger is down. */
    val idle get() = active.isEmpty()

    /** Whether [tick] has work to do: a drag running, a long press or a key repeat pending. */
    val ticking get() = active.any { it.drag != null || holding(it) || resting(it) || holdingUp(it) }

    /** Still, short of the threshold: nothing decided yet. */
    private fun still(t: Touch) = t.drag == null && t.way == null

    private fun holding(t: Touch) = t.key.longPress != null && still(t) && !t.armed

    private fun holdingUp(t: Touch) = t.key.longPress != null && t.way == Way.UP && t.drag == null

    private fun resting(t: Touch) = t.key.repeats && still(t)
    /**
     * The direction of a displacement on [key], or null below the threshold.
     * Each diagonal the key types something on, an alternate or up-right for
     * its long press, is read from 30° to 65° off vertical, taking a slice
     * of both its neighbours.
     */
    private fun way(key: Key, dx: Float, dy: Float): Way? {
        if (hypot(dx, dy) >= thresholdPx) {
            val fromUp = (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI + 360.0) % 360.0
            for ((way, from) in BANDS) {
                val wanted = key.alternates.containsKey(way.direction) ||
                    (way == Way.UP_RIGHT && key.longPress != null)
                if (wanted && fromUp >= from && fromUp < from + 35.0) return way
            }
        }
        return way(dx, dy)
    }

    private companion object {
        /** Each diagonal band's start, in degrees clockwise from up. */
        val BANDS = listOf(Way.UP_RIGHT to 30.0, Way.DOWN_RIGHT to 115.0, Way.DOWN_LEFT to 210.0, Way.UP_LEFT to 295.0)
    }

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
     * Finger [id] went down on [key] at ([x], [y]) at [timeMs]. [positions]
     * holds the current position of every other finger, used to finish them.
     */
    fun down(id: Int, key: Key?, x: Float, y: Float, positions: Map<Int, Point>, timeMs: Long = 0): List<TouchEvent> {
        val (keep, finish) = active.partition { it.drag != null }
        val events = finish.flatMap { t ->
            val p = positions[t.id] ?: Point(t.x, t.y)
            finished(t, p.x, p.y, timeMs)
        }
        active.retainAll(keep)
        // Typing during a drag would land inside its selection or recall
        // and leave the drag's offsets pointing at changed text.
        if (key != null && keep.isEmpty()) active += Touch(id, key, x, y, timeMs)
        return events
    }

    /** Finger [id] moved to ([x], [y]) at [timeMs]. Starts or advances a drag. */
    fun move(id: Int, x: Float, y: Float, timeMs: Long): List<TouchEvent> {
        val t = active.find { it.id == id } ?: return emptyList()
        if (t.drag != null) return listOfNotNull(glide(t, x, timeMs))
        if (t.repeating) return emptyList()
        if (t.armed) {
            // Up from an armed long press: the capital, at once.
            if (t.armY - y <= thresholdPx) return emptyList()
            active.remove(t)
            return listOf(TouchEvent.Press(t.key, Gesture.HoldUp))
        }
        val (prevX, prevMs, sampled) = Triple(t.lastX, t.lastMs, t.sampled)
        t.lastX = x
        t.lastY = y
        t.lastMs = timeMs
        t.sampled = true
        val sideways = t.key.sideways ?: return emptyList()
        if (t.way == Way.UP) {
            // Still moving: the rest that makes a capital long press starts over.
            if (hypot(x - t.restX, y - t.restY) > thresholdPx / 2) rest(t, x, y, timeMs)
            return emptyList()
        }
        if (t.way != null) return emptyList()
        val way = way(t.key, x - t.x, y - t.y) ?: return emptyList()
        t.way = way
        if (way == Way.UP) rest(t, x, y, timeMs)
        if (way != Way.LEFT && way != Way.RIGHT) return emptyList()
        val drag = dragFor(sideways, way)
        t.drag = drag
        // A finger that crossed the threshold in one sample is as fast as it gets.
        t.raw = if (sampled && timeMs > prevMs) abs(x - prevX) * 1000f / (timeMs - prevMs) else Float.POSITIVE_INFINITY
        if (t.raw.isFinite()) t.speed = t.raw
        t.startMs = timeMs
        t.seenMs = timeMs
        t.pushedMs = timeMs
        t.edgeSide = side(t)
        t.edgeSinceMs = timeMs
        t.step = firstStep(drag, way)
        t.words = chained(drag, t.step, timeMs)
        return listOf(TouchEvent.Act(KeyAction.DragBy(drag, t.step, t.words)))
    }

    private fun rest(t: Touch, x: Float, y: Float, timeMs: Long) {
        t.restX = x
        t.restY = y
        t.restMs = timeMs
    }

    /** Move [t]'s drag along with its finger, now at [x] at [timeMs]. */
    private fun glide(t: Touch, x: Float, timeMs: Long): TouchEvent? {
        val drag = t.drag ?: return null
        val dx = x - t.lastX
        val ms = timeMs - t.lastMs
        if (ms > 0) {
            t.raw = abs(dx) * 1000f / ms
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
        t.carry += forward(drag, dx) * gain.at(t.speed) * scale(t)
        return report(t, drag)
    }

    /**
     * Bring every finger up to [nowMs]: finish long presses that are due,
     * repeat held keys, push drags whose finger sits in an edge strip, and
     * release what a drag held back once it is no flick.
     */
    fun tick(nowMs: Long): List<TouchEvent> {
        val held = active.filter { holding(it) && nowMs - it.downMs >= holdMs }
        for (t in held) {
            t.armed = true
            t.armY = t.lastY
        }
        val heldUp = active.filter { holdingUp(it) && nowMs - it.restMs >= holdUpMs }
        active.removeAll(heldUp)
        return held.map { TouchEvent.Armed(it.key) } +
            heldUp.map { TouchEvent.Press(it.key, Gesture.HoldUp) } +
            active.flatMap { t -> repeated(t, nowMs) } +
            active.mapNotNull { t -> push(t, nowMs) }
    }

    /** Taps [t]'s key owes by [nowMs]: the first when the delay is up, then at the [repeat] rate. */
    private fun repeated(t: Touch, nowMs: Long): List<TouchEvent> {
        if (!t.repeating) {
            if (!resting(t) || nowMs - t.downMs < holdMs) return emptyList()
            t.repeating = true
            t.owed = 1f
        } else {
            t.owed += repeat.speed(nowMs - t.downMs - holdMs) * (nowMs - t.repeatedMs) / 1000f
        }
        t.repeatedMs = nowMs
        val n = t.owed.toInt()
        t.owed -= n
        return List(n) { TouchEvent.Press(t.key, Gesture.Tap) }
    }

    private fun push(t: Touch, nowMs: Long): TouchEvent? {
        val drag = t.drag ?: return null
        t.seenMs = max(t.seenMs, nowMs)
        if (t.edgeSide != 0) {
            val seconds = (nowMs - t.pushedMs) / 1000f
            t.pushedMs = nowMs
            t.carry += forward(drag, t.edgeSide * edge.speed(nowMs - t.edgeSinceMs) * seconds) * scale(t)
        }
        return report(t, drag)
    }

    /** How much a step counts for [t]: words are slower going. */
    private fun scale(t: Touch) = if (t.words) WORD_STEP else 1f

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
        if (mayFlick(t)) {
            // Fast moves wait for the window to end, so a flick slowing
            // down before it lifts does not show what it held back.
            if (t.raw > flickPxPerS) {
                t.held += whole
                return null
            }
            if (whole == 0) return null
            t.shown += whole
            return TouchEvent.Act(KeyAction.DragBy(drag, whole, t.words))
        }
        val delta = t.held + whole
        t.held = 0
        if (delta == 0) return null
        return TouchEvent.Act(KeyAction.DragBy(drag, delta, t.words))
    }

    private fun mayFlick(t: Touch) = t.seenMs - t.startMs < flickMs

    /** Finger [id] lifted at ([x], [y]) at [timeMs]; empty if it was already finished. */
    fun up(id: Int, x: Float, y: Float, timeMs: Long = 0): List<TouchEvent> {
        val i = active.indexOfFirst { it.id == id }
        if (i < 0) return emptyList()
        return finished(active.removeAt(i), x, y, timeMs)
    }

    /** The gesture was taken away, e.g. by the system; drop everything. */
    fun cancel(): List<TouchEvent> {
        val events = active.mapNotNull { t -> t.drag?.let { TouchEvent.Act(KeyAction.DragEnd(it, keep = false)) } }
        active.clear()
        return events
    }

    private fun finished(t: Touch, x: Float, y: Float, timeMs: Long): List<TouchEvent> {
        // A repeating key already did its work.
        if (t.repeating) return emptyList()
        if (t.armed) return listOf(TouchEvent.Press(t.key, Gesture.Hold))
        t.drag?.let { drag ->
            // A flick keeps only its first step. Otherwise land where the
            // finger lifted, even if no move event got there.
            val flick = mayFlick(t)
            val last = when {
                !flick -> glide(t, x, t.lastMs)
                t.shown != 0 -> TouchEvent.Act(KeyAction.DragBy(drag, -t.shown, t.words))
                else -> null
            }
            lastFlick = if (flick) Flick(drag, t.step, max(timeMs, t.seenMs)) else null
            return listOfNotNull(last, TouchEvent.Act(KeyAction.DragEnd(drag)))
        }
        val dx = x - t.x
        val dy = y - t.y
        val sideways = t.key.sideways ?: return listOf(TouchEvent.Press(t.key, classify(dx, dy, thresholdPx)))
        return when (val way = t.way ?: way(t.key, dx, dy)) {
            null -> listOf(TouchEvent.Press(t.key, Gesture.Tap))
            Way.UP, Way.UP_RIGHT, Way.DOWN_RIGHT, Way.DOWN, Way.DOWN_LEFT, Way.UP_LEFT ->
                listOf(TouchEvent.Press(t.key, Gesture.Swipe(way.direction!!)))
            // A flick too quick for any move event: one step, then done.
            Way.LEFT, Way.RIGHT -> {
                val drag = dragFor(sideways, way)
                val step = firstStep(drag, way)
                val words = chained(drag, step, t.downMs)
                lastFlick = Flick(drag, step, max(timeMs, t.downMs))
                return listOf(
                    TouchEvent.Act(KeyAction.DragBy(drag, step, words)),
                    TouchEvent.Act(KeyAction.DragEnd(drag)),
                )
            }
        }.also { lastFlick = null }
    }
}
