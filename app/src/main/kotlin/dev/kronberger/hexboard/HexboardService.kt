package dev.kronberger.hexboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import dev.kronberger.hexboard.core.Face
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Layouts

class HexboardService : InputMethodService() {

    override fun onCreateInputView(): View = KeyboardView(this, Layouts.english, ::onFace)

    // Landscape would otherwise hand the whole screen to an extract view.
    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun onFace(face: Face) {
        val ic = currentInputConnection ?: return
        when (val action = face.action) {
            is KeyAction.Text -> ic.commitText(action.text, 1)
            KeyAction.Space -> ic.commitText(" ", 1)
            // Placeholders: grapheme-aware delete is Phase 5, imeOptions-aware
            // enter is Phase 6.
            KeyAction.Delete -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            KeyAction.Enter -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            // Shift is Phase 3, symbols Phase 6, emoji Phase 7.
            KeyAction.Shift, KeyAction.Symbols, KeyAction.Emoji -> Unit
        }
    }
}
