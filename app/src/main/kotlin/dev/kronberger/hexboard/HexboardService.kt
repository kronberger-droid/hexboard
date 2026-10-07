package dev.kronberger.hexboard

import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.kronberger.hexboard.core.Cursor
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Keyboard
import dev.kronberger.hexboard.core.Layouts
import dev.kronberger.hexboard.core.Recall
import dev.kronberger.hexboard.core.clusterStart

class HexboardService : InputMethodService() {

    private val cursor = Cursor()
    private val recall = Recall()

    /** A backspace scrub in progress: where it started and the text it can reach. */
    private class Scrub(val cursor: Int, val before: String, val boundaries: List<Int>)
    private var scrub: Scrub? = null

    override fun onCreateInputView(): View = KeyboardView(this, Keyboard(Layouts.english), ::onAction)

    // Landscape would otherwise hand the whole screen to an extract view.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cursor.reset(attribute.initialSelStart, attribute.initialSelEnd)
        recall.clear()
        scrub = null
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (cursor.reported(newSelStart, newSelEnd)) recall.clear()
    }

    private fun onAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Text -> type(ic, action.text)
            KeyAction.Space -> type(ic, " ")
            // Placeholder: imeOptions-aware enter is Phase 6.
            KeyAction.Enter -> {
                recall.clear()
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            KeyAction.Delete -> deleteBack(ic)
            is KeyAction.ScrubTo -> scrubTo(ic, action.steps)
            is KeyAction.ScrubEnd -> scrubEnd(ic, action.steps)
            KeyAction.Recall -> recall.take()?.let { insert(ic, it) }
            // Shift never gets here; Keyboard consumes it. Symbols are Phase 6,
            // emoji Phase 7.
            KeyAction.Shift, KeyAction.Symbols, KeyAction.Emoji -> Unit
        }
    }

    private fun type(ic: InputConnection, text: String) {
        recall.clear()
        insert(ic, text)
    }

    /** Commit [text] over the selection and keep [cursor] in step. */
    private fun insert(ic: InputConnection, text: String) {
        ic.commitText(text, 1)
        if (cursor.known) cursor.movedBySelf(cursor.start + text.length)
    }

    /** Delete the selection, or else one grapheme cluster before the cursor. */
    private fun deleteBack(ic: InputConnection) {
        if (cursor.known && cursor.start < cursor.end) {
            val (start, end) = cursor.start to cursor.end
            val selected = ic.getSelectedText(0)?.toString()
            ic.commitText("", 1)
            cursor.movedBySelf(start)
            if (selected != null) recall.record(selected, end, start) else recall.clear()
            return
        }
        val before = ic.getTextBeforeCursor(LOOKBACK, 0)
        if (before.isNullOrEmpty() || !cursor.known) {
            // Nothing visible to us: an empty field, or an editor such as a
            // terminal that does not expose its text. A key event still works.
            recall.clear()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val start = clusterStart(boundaries(before), 1)
        val length = before.length - start
        ic.deleteSurroundingText(length, 0)
        val from = cursor.start
        cursor.movedBySelf(from - length)
        recall.record(before.substring(start), from, from - length)
    }

    private fun scrubTo(ic: InputConnection, steps: Int) {
        val s = scrub ?: startScrub(ic) ?: return
        val start = selectionStart(s, steps)
        ic.setSelection(start, s.cursor)
        cursor.movedBySelf(start, s.cursor)
    }

    private fun startScrub(ic: InputConnection): Scrub? {
        if (!cursor.known) return null
        val before = ic.getTextBeforeCursor(SCRUB_LOOKBACK, 0)?.toString() ?: return null
        return Scrub(cursor.start, before, boundaries(before)).also { scrub = it }
    }

    private fun scrubEnd(ic: InputConnection, steps: Int) {
        val s = scrub ?: return
        scrub = null
        val start = selectionStart(s, steps)
        if (start == s.cursor) {
            ic.setSelection(s.cursor, s.cursor)
            cursor.movedBySelf(s.cursor)
            return
        }
        ic.beginBatchEdit()
        ic.setSelection(start, s.cursor)
        ic.commitText("", 1)
        ic.endBatchEdit()
        cursor.movedBySelf(start)
        recall.record(s.before.substring(s.before.length - (s.cursor - start)), s.cursor, start)
    }

    /** Absolute editor offset where a scrub of [steps] clusters starts. */
    private fun selectionStart(s: Scrub, steps: Int) =
        s.cursor - (s.before.length - clusterStart(s.boundaries, steps))

    /** Grapheme cluster boundaries of [text], from 0 to its length. */
    private fun boundaries(text: CharSequence): List<Int> {
        val clusters = BreakIterator.getCharacterInstance()
        clusters.setText(text.toString())
        return generateSequence(clusters.first()) { clusters.next().takeIf { it != BreakIterator.DONE } }.toList()
    }

    private companion object {
        /** Enough for any single cluster, including long ZWJ emoji sequences. */
        const val LOOKBACK = 64

        /** How far back one scrub can reach. */
        const val SCRUB_LOOKBACK = 2000
    }
}
