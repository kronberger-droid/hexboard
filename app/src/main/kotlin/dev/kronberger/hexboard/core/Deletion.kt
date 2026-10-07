package dev.kronberger.hexboard.core

/**
 * Where a selection reaching [steps] grapheme clusters back from the end of
 * a text starts. [boundaries] are the text's cluster boundaries, ascending
 * from 0 to its length; the Android side finds them with ICU. Steps past the
 * start of the text stop there.
 */
fun clusterStart(boundaries: List<Int>, steps: Int): Int =
    boundaries[(boundaries.size - 1 - steps).coerceAtLeast(0)]

/**
 * Our view of the editor's selection. The editor reports selection changes
 * asynchronously, so after two quick edits the report for the first can
 * arrive after the second. Positions our own edits produced are therefore
 * remembered until reported; a report of one of them is ours and possibly
 * stale, anything else means the selection was moved from outside.
 */
class Cursor {
    var start = -1
        private set
    var end = -1
        private set

    /** Selections our edits produced, not yet reported, oldest first. */
    private val pending = ArrayDeque<Pair<Int, Int>>()

    val known get() = start >= 0

    fun reset(selStart: Int, selEnd: Int) {
        start = selStart
        end = selEnd
        pending.clear()
    }

    /** One of our edits left the selection at [selStart]..[selEnd]. */
    fun movedBySelf(selStart: Int, selEnd: Int = selStart) {
        start = selStart
        end = selEnd
        pending.addLast(selStart to selEnd)
    }

    /** The editor reported a selection; true if somebody else moved it. */
    fun reported(selStart: Int, selEnd: Int): Boolean {
        val i = pending.indexOf(selStart to selEnd)
        if (i < 0) {
            reset(selStart, selEnd)
            return true
        }
        repeat(i + 1) { pending.removeFirst() }
        return false
    }
}

/**
 * The text deleted most recently, so a swipe right on backspace can put it
 * back. Deletions made one after another at the same spot pile up into one
 * run. Typing or moving the cursor elsewhere should [clear] it.
 */
class Recall {
    private var text = ""

    /** Cursor position right after the last recorded deletion. */
    private var cursor = -1

    fun record(deleted: String, cursorBefore: Int, cursorAfter: Int) {
        text = if (text.isNotEmpty() && cursor == cursorBefore) deleted + text else deleted
        cursor = cursorAfter
    }

    /** The run to re-insert, consumed. */
    fun take(): String? = text.ifEmpty { null }.also { clear() }

    fun clear() {
        text = ""
        cursor = -1
    }
}
