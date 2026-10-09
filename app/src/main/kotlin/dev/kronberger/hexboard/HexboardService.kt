package dev.kronberger.hexboard

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.Log
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
import dev.kronberger.hexboard.core.Keymaps
import dev.kronberger.hexboard.core.Layouts
import dev.kronberger.hexboard.core.Recall
import dev.kronberger.hexboard.core.Settings
import dev.kronberger.hexboard.core.clusterEnd
import dev.kronberger.hexboard.core.clusterStart
import dev.kronberger.hexboard.core.parseEmojiAsset
import dev.kronberger.hexboard.core.periodForDoubleSpace
import dev.kronberger.hexboard.core.stepClusters

class HexboardService : InputMethodService() {

    private val cursor = Cursor()
    private val recall = Recall()

    /**
     * A scrub in progress: the selection's end it grows back from, the text
     * it can reach so far, how much is selected, and whether the editor has
     * more text before it. [from] is where the selection started, the same
     * as [cursor] unless the scrub began over one.
     */
    private class Scrub(val cursor: Int, val from: Int, var before: String, var boundaries: List<Int>) {
        var steps = 0
        var more = before.length >= WINDOW
    }

    /** A recall drag in progress: where it inserts, the run, how much is shown. */
    private class Restore(val cursor: Int, val run: String, val boundaries: List<Int>) {
        var steps = 0
    }

    /**
     * Text around the selection at the start of a cursor or selection drag,
     * from editor offset [start], with its cluster boundaries. [anchor] stays
     * put while [focus] moves; for a cursor drag they are the same. The
     * editor may hold more text on either side, fetched as the drag gets there.
     */
    private class Travel(var start: Int, var text: String, var boundaries: List<Int>, var anchor: Int, var focus: Int) {
        var moreBefore = false
        var moreAfter = false
        val end get() = start + text.length

        fun step(from: Int, n: Int) = start + stepClusters(boundaries, from - start, n)
    }

    /**
     * The last action typed a space that could start a double space; the
     * third space in a row does not, so it never stacks periods.
     */
    private var lastWasSpace = false

    /**
     * A drag in an editor that does not show us its text, such as a
     * terminal, done with key events step by step instead.
     */
    private var blind: Drag? = null

    private var scrub: Scrub? = null
    private var restore: Restore? = null
    private var travel: Travel? = null

    private val keyboard = Keyboard(Layouts.english, Layouts.symbols)

    /** The keymap text the keyboard's layers were last built from. */
    private var loadedKeymap: String? = null
    private var keymapLoaded = false
    private val prefs by lazy { Prefs.of(this) }
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
        loadKeymap()
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
        view?.configure(prefs)
        applyPalette(
            when (prefs[Settings.theme]) {
                1 -> Palette.LIGHT
                2 -> Palette.DARK
                else -> Palette.of(resources)
            },
        )
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

    /**
     * Use the stored keymap, parsing it only when its text changed. One that
     * fails to parse, which the editor never saves, falls back to the default.
     */
    private fun loadKeymap() {
        val text = prefs.keymap
        if (keymapLoaded && text == loadedKeymap) return
        keymapLoaded = true
        loadedKeymap = text
        val keymap = text?.let {
            runCatching { Keymaps.parse(it) }
                .onFailure { e -> Log.w("Hexboard", "stored keymap rejected, using the default: ${e.message}") }
                .getOrNull()
        }
        keyboard.setLayouts(keymap?.letters ?: Layouts.english, keymap?.symbols ?: Layouts.symbols)
    }

    /** Shift for a capital when the editor expects one, e.g. at a sentence start. */
    private fun updateCaps() {
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val wanted = prefs[Settings.autoCaps] && info != null && ic != null && info.inputType != InputType.TYPE_NULL &&
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
        lastWasSpace = false
        scrub = null
        restore = null
        travel = null
        blind = null
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (cursor.reported(newSelStart, newSelEnd)) {
            recall.clear()
            lastWasSpace = false
            updateCaps()
        }
    }

    private fun onAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Text -> type(ic, action.text)
            KeyAction.Space -> if (!(lastWasSpace && periodForSpace(ic))) type(ic, " ")
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
            is KeyAction.DragBy -> when {
                blind == action.drag -> blindBy(ic, action.drag, action.delta)
                action.drag == Drag.SCRUB -> scrubBy(ic, action.delta)
                action.drag == Drag.RECALL -> recallBy(ic, action.delta)
                else -> travelBy(ic, action.drag, action.delta)
            }
            is KeyAction.DragEnd -> if (blind == action.drag) blind = null else when (action.drag) {
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
        lastWasSpace = action == KeyAction.Space && !lastWasSpace
    }

    /**
     * On the second of two spaces, turn the first into `. ` if it follows a
     * word. False, changing nothing, when it does not apply here.
     */
    private fun periodForSpace(ic: InputConnection): Boolean {
        if (!prefs[Settings.doubleSpace] || !cursor.known || cursor.start != cursor.end) return false
        val variation = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        if (variation in NO_PERIOD_VARIATIONS) return false
        // Fields that ask for no suggestions want their text as typed.
        if ((currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        val before = ic.getTextBeforeCursor(2, 0) ?: return false
        if (!periodForDoubleSpace(before)) return false
        recall.clear()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
        ic.endBatchEdit()
        cursor.movedBySelf(cursor.start + 1)
        return true
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
        val s = scrub ?: startScrub(ic) ?: return goBlind(ic, Drag.SCRUB, delta)
        if (s.more && s.steps + delta > s.boundaries.size - 1) reachFurther(ic, s)
        // Clamped here, so turning back reacts at once however long the
        // finger was held past the start of the text.
        s.steps = (s.steps + delta).coerceIn(0, s.boundaries.size - 1)
        val start = selectionStart(s)
        ic.setSelection(start, s.cursor)
        cursor.movedBySelf(start, s.cursor)
    }

    /**
     * A scrub over a selection starts with that selection taken, and its
     * first step is the selection itself, so a flick deletes exactly that.
     */
    private fun startScrub(ic: InputConnection): Scrub? {
        if (!cursor.known) return null
        val before = ic.getTextBeforeCursor(WINDOW, 0)?.toString().orEmpty()
        val selected = if (cursor.start < cursor.end) ic.getSelectedText(0)?.toString().orEmpty() else ""
        val text = before + selected
        if (text.isEmpty()) return null
        val boundaries = boundaries(text)
        val s = Scrub(cursor.start + selected.length, cursor.start, text, boundaries)
        val taken = boundaries.size - 1 - boundaries.indexOfFirst { it >= before.length }
        if (taken > 0) s.steps = taken - 1
        return s.also { scrub = it }
    }

    private fun scrubEnd(ic: InputConnection, keep: Boolean) {
        val s = scrub ?: return
        scrub = null
        val start = selectionStart(s)
        if (!keep) {
            ic.setSelection(s.from, s.cursor)
            cursor.movedBySelf(s.from, s.cursor)
            return
        }
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
     * Fetch the text before what [s] can reach. The editor answers relative
     * to the selection, so that is first stretched to where the scrub is
     * heading anyway.
     */
    private fun reachFurther(ic: InputConnection, s: Scrub) {
        val from = s.cursor - s.before.length
        ic.setSelection(from, s.cursor)
        cursor.movedBySelf(from, s.cursor)
        val more = ic.getTextBeforeCursor(WINDOW, 0)?.toString().orEmpty()
        s.more = more.length >= WINDOW
        if (more.isEmpty()) return
        s.before = more + s.before
        s.boundaries = boundaries(s.before)
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

    /** Carry on [drag] with key events, the way a backspace tap does when no text is visible. */
    private fun goBlind(ic: InputConnection, drag: Drag, delta: Int) {
        blind = drag
        blindBy(ic, drag, delta)
    }

    /**
     * One key event per step: deletes for a scrub, arrows for the cursor,
     * shifted arrows for a selection. A scrub cannot take back what it
     * deleted, and there is nothing to recall.
     */
    private fun blindBy(ic: InputConnection, drag: Drag, delta: Int) {
        val code = when {
            drag == Drag.SCRUB -> if (delta > 0) KeyEvent.KEYCODE_DEL else return
            drag == Drag.RECALL -> return
            delta < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            else -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        val meta = if (drag == Drag.SELECT) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        repeat(kotlin.math.abs(delta)) {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
    }

    /** Move the cursor, or the selection's moving end, by [delta] clusters. */
    private fun travelBy(ic: InputConnection, drag: Drag, delta: Int) {
        val t = travel ?: startTravel(ic, drag) ?: return goBlind(ic, drag, delta)
        var focus = t.step(t.focus, delta)
        if ((focus == t.start && delta < 0 && t.moreBefore) || (focus == t.end && delta > 0 && t.moreAfter)) {
            reachFurther(ic, t, drag, delta < 0)
            focus = t.step(t.focus, delta)
        }
        t.focus = focus
        if (drag == Drag.MOVE) t.anchor = t.focus
        val (start, end) = minOf(t.anchor, t.focus) to maxOf(t.anchor, t.focus)
        ic.setSelection(start, end)
        cursor.movedBySelf(start, end)
    }

    /**
     * Fetch the text past one end of what [t] can reach, [left] or right.
     * The editor answers relative to the selection, so that is first moved
     * to that end, where the drag is heading anyway.
     */
    private fun reachFurther(ic: InputConnection, t: Travel, drag: Drag, left: Boolean) {
        val edge = if (left) t.start else t.end
        val (a, b) = if (drag == Drag.SELECT) minOf(t.anchor, edge) to maxOf(t.anchor, edge) else edge to edge
        ic.setSelection(a, b)
        cursor.movedBySelf(a, b)
        if (left) {
            val more = ic.getTextBeforeCursor(WINDOW, 0)?.toString().orEmpty()
            t.moreBefore = more.length >= WINDOW
            t.text = more + t.text
            t.start -= more.length
        } else {
            val more = ic.getTextAfterCursor(WINDOW, 0)?.toString().orEmpty()
            t.moreAfter = more.length >= WINDOW
            t.text += more
        }
        t.boundaries = boundaries(t.text)
    }

    private fun startTravel(ic: InputConnection, drag: Drag): Travel? {
        if (!cursor.known) return null
        val before = ic.getTextBeforeCursor(WINDOW, 0)?.toString() ?: return null
        val selected = if (cursor.start < cursor.end) ic.getSelectedText(0)?.toString().orEmpty() else ""
        val after = ic.getTextAfterCursor(WINDOW, 0)?.toString().orEmpty()
        if (before.isEmpty() && selected.isEmpty() && after.isEmpty()) return null
        recall.clear()
        // A selection drag moves the end of any existing selection; a cursor
        // drag starts from that end, collapsed.
        val (anchor, focus) = if (drag == Drag.SELECT) cursor.start to cursor.end else cursor.end to cursor.end
        val start = cursor.start - before.length
        val text = before + selected + after
        return Travel(start, text, boundaries(text), anchor, focus).also {
            it.moreBefore = before.length >= WINDOW
            it.moreAfter = after.length >= WINDOW
            travel = it
        }
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

        /** Fields where a period is never typed for the user. */
        val NO_PERIOD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )

        /** How much text a drag fetches at a time on either side of where it is. */
        const val WINDOW = 2000
    }
}
