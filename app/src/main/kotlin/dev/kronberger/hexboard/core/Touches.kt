package dev.kronberger.hexboard.core

/** A finished touch: the key it went down on and what the finger did. */
data class Press(val key: Key, val gesture: Gesture)

/**
 * Tracks every finger on the keyboard by pointer id and turns them into
 * [Press]es in touch-down order.
 *
 * A press normally finishes when its finger lifts. When another finger
 * lands first, every finger already down is finished on the spot, judged
 * from where it is now. In rolling typing the earlier key can otherwise
 * be released after the later one and come out second.
 */
class Touches(private val thresholdPx: Float) {

    private class Touch(val id: Int, val key: Key, val x: Float, val y: Float)

    /** Fingers still down, oldest first. */
    private val active = mutableListOf<Touch>()

    /**
     * Finger [id] went down on [key] at ([x], [y]). [positions] holds the
     * current position of every other finger, used to finish them.
     */
    fun down(id: Int, key: Key?, x: Float, y: Float, positions: Map<Int, Point>): List<Press> {
        val finished = active.map { t ->
            val p = positions[t.id] ?: Point(t.x, t.y)
            Press(t.key, classify(p.x - t.x, p.y - t.y, thresholdPx))
        }
        active.clear()
        if (key != null) active += Touch(id, key, x, y)
        return finished
    }

    /** Finger [id] lifted at ([x], [y]); null if it was already finished. */
    fun up(id: Int, x: Float, y: Float): Press? {
        val i = active.indexOfFirst { it.id == id }
        if (i < 0) return null
        val t = active.removeAt(i)
        return Press(t.key, classify(x - t.x, y - t.y, thresholdPx))
    }

    /** The gesture was taken away, e.g. by the system; drop everything. */
    fun cancel() = active.clear()
}
