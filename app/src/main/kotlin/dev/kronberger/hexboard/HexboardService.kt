package dev.kronberger.hexboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import dev.kronberger.hexboard.core.Key
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Layouts

class HexboardService : InputMethodService() {

    override fun onCreateInputView(): View = KeyboardView(this, Layouts.english, ::onKey)

    // Landscape would otherwise hand the whole screen to an extract view.
    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        when (val action = key.action) {
            is KeyAction.Text -> ic.commitText(action.text, 1)
            KeyAction.Space -> ic.commitText(" ", 1)
            // Placeholders: grapheme-aware delete is Phase 5, imeOptions-aware
            // enter is Phase 6.
            KeyAction.Delete -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            KeyAction.Enter -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }
}
