package dev.kronberger.hexboard

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SelectRangeGesture
import android.widget.FrameLayout
import dev.kronberger.hexboard.core.EmojiGroup
import dev.kronberger.hexboard.core.Cursor
import dev.kronberger.hexboard.core.Drag
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Keyboard
import dev.kronberger.hexboard.core.Layouts
import dev.kronberger.hexboard.core.Recall
import dev.kronberger.hexboard.core.clusterEnd
import dev.kronberger.hexboard.core.clusterStart
import dev.kronberger.hexboard.core.parseEmojiAsset
import dev.kronberger.hexboard.core.stepClusters

class HexboardService : InputMethodService() {

    private val cursor = Cursor()
    private val recall = Recall()

    /** A scrub in progress: where it started, the text it can reach, how much is selected. */
    private class Scrub(val cursor: Int, val before: String, val boundaries: List<Int>) {
        var steps = 0
    }

    /** A recall drag in progress: where it inserts, the run, how much is shown. */
    private class Restore(val cursor: Int, val run: String, val boundaries: List<Int>) {
        var steps = 0
    }

    /**
     * Text around the selection at the start of a cursor or selection drag,
     * with cluster boundaries in editor offsets. [anchor] stays put while
     * [focus] moves; for a cursor drag they are the same.
     */
    private class Travel(val start: Int, val boundaries: List<Int>, var anchor: Int, var focus: Int) {
        fun step(from: Int, n: Int) = start + stepClusters(boundaries, from - start, n)
    }

    private var scrub: Scrub? = null
    private var restore: Restore? = null
    private var travel: Travel? = null

    private val keyboard = Keyboard(Layouts.english, Layouts.symbols)
    private var view: KeyboardView? = null
    private var emojiPanel: EmojiPanelView? = null

    /**
     * The keys and the emoji panel stacked in one frame. The panel copies
     * the keys' height, and the keys stay laid out but invisible under it,
     * so switching never resizes the window.
     */
    override fun onCreateInputView(): View {
        val keys = KeyboardView(this, keyboard, ::onAction)
        val panel = EmojiPanelView(this, lazy { emojiCatalog() }, getSharedPreferences("hexboard", MODE_PRIVATE), keys, ::onAction)
        panel.visibility = View.GONE
        view = keys
        emojiPanel = panel
        return FrameLayout(this).apply {
            addView(keys)
            addView(panel)
        }
    }

    /** The bundled emoji, minus any this device's font cannot draw. */
    private fun emojiCatalog(): List<EmojiGroup> {
        val paint = Paint()
        val text = assets.open("emoji.txt").bufferedReader().use { it.readText() }
        return parseEmojiAsset(text)
            .map { g -> g.copy(emoji = g.emoji.filter(paint::hasGlyph)) }
            .filter { it.emoji.isNotEmpty() }
    }

    private fun showEmoji(show: Boolean) {
        val panel = emojiPanel ?: return
        if (show) panel.refresh()
        panel.visibility = if (show) View.VISIBLE else View.GONE
        view?.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Numbers, phone numbers and dates start on the layer with digits.
        keyboard.showingSymbols = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
        keyboard.enterLabel = when (editorAction(info)) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> null
        }
        showEmoji(false)
        applyPalette(Palette.of(resources))
        updateCaps()
    }

    private fun applyPalette(p: Palette) {
        view?.applyPalette(p)
        emojiPanel?.applyPalette(p)
        // The IME switcher strip is drawn over the keyboard's own background.
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.window?.insetsController?.setSystemBarsAppearance(if (p.light) light else 0, light)
        }
    }

    /** Shift for a capital when the editor expects one, e.g. at a sentence start. */
    private fun updateCaps() {
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val wanted = info != null && ic != null && info.inputType != InputType.TYPE_NULL &&
            ic.getCursorCapsMode(info.inputType) != 0
        keyboard.autoCaps(wanted)
        view?.invalidate()
    }

    /**
     * The editor action enter should perform, or null for a plain newline:
     * when the editor asks for none, or explicitly wants enter to stay a
     * newline (`IME_FLAG_NO_ENTER_ACTION`, set by most multi-line fields).
     */
    private fun editorAction(info: EditorInfo?): Int? {
        info ?: return null
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return (info.imeOptions and EditorInfo.IME_MASK_ACTION)
            .takeIf { it != EditorInfo.IME_ACTION_NONE && it != EditorInfo.IME_ACTION_UNSPECIFIED }
    }

    // Landscape would otherwise hand the whole screen to an extract view.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cursor.reset(attribute.initialSelStart, attribute.initialSelEnd)
        recall.clear()
        scrub = null
        restore = null
        travel = null
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (cursor.reported(newSelStart, newSelEnd)) {
            recall.clear()
            updateCaps()
        }
    }

    private fun onAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Text -> type(ic, action.text)
            KeyAction.Space -> type(ic, " ")
            KeyAction.Enter -> {
                recall.clear()
                val editorAction = editorAction(currentInputEditorInfo)
                if (editorAction != null) {
                    ic.performEditorAction(editorAction)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
            KeyAction.Delete -> deleteBack(ic)
            is KeyAction.DragBy -> when (action.drag) {
                Drag.SCRUB -> scrubBy(ic, action.delta)
                Drag.RECALL -> recallBy(ic, action.delta)
                Drag.MOVE, Drag.SELECT -> travelBy(ic, action.drag, action.delta)
            }
            is KeyAction.DragEnd -> when (action.drag) {
                Drag.SCRUB -> scrubEnd(ic, action.keep)
                Drag.RECALL -> recallEnd(ic, action.keep)
                Drag.MOVE -> travel = null
                Drag.SELECT -> {
                    travel = null
                    if (action.keep) showSelectionToolbar(ic, cursor.start, cursor.end)
                }
            }
            KeyAction.Emoji -> showEmoji(true)
            // Only the emoji panel sends this; the keys switch layers in Keyboard.
            KeyAction.Letters -> showEmoji(false)
            // Keyboard consumes these.
            KeyAction.Shift, KeyAction.Symbols -> Unit
        }
        // The editor answers in order, so this already sees the edit above.
        if (action !is KeyAction.DragBy) updateCaps()
    }

    private fun type(ic: InputConnection, text: String) {
        recall.clear()
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

    private fun scrubBy(ic: InputConnection, delta: Int) {
        val s = scrub ?: startScrub(ic) ?: return
        // Clamped here, so turning back reacts at once however long the
        // finger was held past the start of the text.
        s.steps = (s.steps + delta).coerceIn(0, s.boundaries.size - 1)
        val start = selectionStart(s)
        ic.setSelection(start, s.cursor)
        cursor.movedBySelf(start, s.cursor)
    }

    private fun startScrub(ic: InputConnection): Scrub? {
        if (!cursor.known) return null
        val before = ic.getTextBeforeCursor(WINDOW, 0)?.toString() ?: return null
        return Scrub(cursor.start, before, boundaries(before)).also { scrub = it }
    }

    private fun scrubEnd(ic: InputConnection, keep: Boolean) {
        val s = scrub ?: return
        scrub = null
        val start = selectionStart(s)
        if (!keep || start == s.cursor) {
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

    /** Absolute editor offset where the scrub's selection starts. */
    private fun selectionStart(s: Scrub) =
        s.cursor - (s.before.length - clusterStart(s.boundaries, s.steps))

    /**
     * Show the first clusters of the recall run as composing text, so turning
     * back can take them out again without touching committed text.
     */
    private fun recallBy(ic: InputConnection, delta: Int) {
        val r = restore ?: startRestore() ?: return
        r.steps = (r.steps + delta).coerceIn(0, r.boundaries.size - 1)
        val shown = r.run.substring(0, clusterEnd(r.boundaries, r.steps))
        ic.setComposingText(shown, 1)
        cursor.movedBySelf(r.cursor + shown.length)
    }

    private fun startRestore(): Restore? {
        val run = recall.run
        if (run.isEmpty() || !cursor.known || cursor.start != cursor.end) return null
        return Restore(cursor.start, run, boundaries(run)).also { restore = it }
    }

    private fun recallEnd(ic: InputConnection, keep: Boolean) {
        val r = restore ?: return
        restore = null
        val length = if (keep) clusterEnd(r.boundaries, r.steps) else 0
        ic.beginBatchEdit()
        ic.setComposingText(r.run.substring(0, length), 1)
        ic.finishComposingText()
        ic.endBatchEdit()
        cursor.movedBySelf(r.cursor + length)
        if (length > 0) recall.restored(length, r.cursor + length)
    }

    /** Move the cursor, or the selection's moving end, by [delta] clusters. */
    private fun travelBy(ic: InputConnection, drag: Drag, delta: Int) {
        val t = travel ?: startTravel(ic, drag) ?: return
        t.focus = t.step(t.focus, delta)
        if (drag == Drag.MOVE) t.anchor = t.focus
        val (start, end) = minOf(t.anchor, t.focus) to maxOf(t.anchor, t.focus)
        ic.setSelection(start, end)
        cursor.movedBySelf(start, end)
    }

    private fun startTravel(ic: InputConnection, drag: Drag): Travel? {
        if (!cursor.known) return null
        val before = ic.getTextBeforeCursor(WINDOW, 0)?.toString() ?: return null
        val selected = if (cursor.start < cursor.end) ic.getSelectedText(0)?.toString().orEmpty() else ""
        val after = ic.getTextAfterCursor(WINDOW, 0)?.toString().orEmpty()
        recall.clear()
        // A selection drag moves the end of any existing selection; a cursor
        // drag starts from that end, collapsed.
        val (anchor, focus) = if (drag == Drag.SELECT) cursor.start to cursor.end else cursor.end to cursor.end
        val start = cursor.start - before.length
        return Travel(start, boundaries(before + selected + after), anchor, focus).also { travel = it }
    }

    /**
     * Open the editor's own cut/copy/paste toolbar over [start]..[end].
     *
     * An IME has no direct way to do this; setting the selection never shows
     * it. A handwriting select gesture does: the editor applies it and then
     * starts its selection action mode. The gesture names screen areas, not
     * offsets, so first ask where the selection's first and last characters
     * are drawn and aim at exactly those. Needs API 34 and an editor that
     * takes the gesture; otherwise the selection simply stays as it is.
     */
    private fun showSelectionToolbar(ic: InputConnection, start: Int, end: Int) {
        if (Build.VERSION.SDK_INT < 34 || start < 0 || start >= end) return
        val info = currentInputEditorInfo ?: return
        if (SelectRangeGesture::class.java !in info.supportedHandwritingGestures) return
        val screen = resources.displayMetrics
        val everywhere = RectF(0f, 0f, screen.widthPixels.toFloat(), screen.heightPixels.toFloat())
        ic.requestTextBoundsInfo(everywhere, mainExecutor) { result ->
            val bounds = result.textBoundsInfo ?: return@requestTextBoundsInfo
            if (start < bounds.startIndex || end > bounds.endIndex) return@requestTextBoundsInfo
            val toScreen = Matrix().also(bounds::getMatrix)
            fun charArea(i: Int) = RectF().also {
                bounds.getCharacterBounds(i, it)
                toScreen.mapRect(it)
            }
            val gesture = SelectRangeGesture.Builder()
                .setSelectionStartArea(charArea(start))
                .setSelectionEndArea(charArea(end - 1))
                .setGranularity(HandwritingGesture.GRANULARITY_CHARACTER)
                .build()
            currentInputConnection?.performHandwritingGesture(gesture, null, null)
        }
    }

    /** Grapheme cluster boundaries of [text], from 0 to its length. */
    private fun boundaries(text: CharSequence): List<Int> {
        val clusters = BreakIterator.getCharacterInstance()
        clusters.setText(text.toString())
        return generateSequence(clusters.first()) { clusters.next().takeIf { it != BreakIterator.DONE } }.toList()
    }

    private companion object {
        /** Enough for any single cluster, including long ZWJ emoji sequences. */
        const val LOOKBACK = 64

        /** How far one drag can reach on either side of where it started. */
        const val WINDOW = 2000
    }
}
