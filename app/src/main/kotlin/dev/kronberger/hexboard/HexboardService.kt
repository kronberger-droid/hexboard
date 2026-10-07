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
import dev.kronberger.hexboard.core.clusterEnd
import dev.kronberger.hexboard.core.clusterStart

class HexboardService : InputMethodService() {

    private val cursor = Cursor()
    private val recall = Recall()

    /** A scrub in progress: where it started and the text it can reach. */
    private class Scrub(val cursor: Int, val before: String, val boundaries: List<Int>)
    private var scrub: Scrub? = null

    /** A recall drag in progress: where it inserts and the run it can bring back. */
    private class Restore(val cursor: Int, val run: String, val boundaries: List<Int>)
    private var restore: Restore? = null

    override fun onCreateInputView(): View = KeyboardView(this, Keyboard(Layouts.english), ::onAction)

    // Landscape would otherwise hand the whole screen to an extract view.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cursor.reset(attribute.initialSelStart, attribute.initialSelEnd)
        recall.clear()
        scrub = null
        restore = null
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
            is KeyAction.RecallTo -> recallTo(ic, action.steps)
            is KeyAction.RecallEnd -> recallEnd(ic, action.steps)
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
        // A quick flick ends without any preview having started the scrub.
        val s = scrub ?: startScrub(ic) ?: return
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

    /**
     * Show the first [steps] clusters of the recall run as composing text, so
     * dragging back can take them out again without touching committed text.
     */
    private fun recallTo(ic: InputConnection, steps: Int) {
        val r = restore ?: startRestore() ?: return
        val shown = r.run.substring(0, clusterEnd(r.boundaries, steps))
        ic.setComposingText(shown, 1)
        cursor.movedBySelf(r.cursor + shown.length)
    }

    private fun startRestore(): Restore? {
        val run = recall.run
        if (run.isEmpty() || !cursor.known || cursor.start != cursor.end) return null
        return Restore(cursor.start, run, boundaries(run)).also { restore = it }
    }

    private fun recallEnd(ic: InputConnection, steps: Int) {
        // A quick flick ends without any preview having started the recall.
        val r = restore ?: startRestore() ?: return
        restore = null
        val length = clusterEnd(r.boundaries, steps)
        ic.beginBatchEdit()
        ic.setComposingText(r.run.substring(0, length), 1)
        ic.finishComposingText()
        ic.endBatchEdit()
        cursor.movedBySelf(r.cursor + length)
        recall.restored(length, r.cursor + length)
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
