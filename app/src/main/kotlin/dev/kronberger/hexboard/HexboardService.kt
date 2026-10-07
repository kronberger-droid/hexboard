package dev.kronberger.hexboard

import android.inputmethodservice.InputMethodService
import android.view.View

class HexboardService : InputMethodService() {

    override fun onCreateInputView(): View =
        KeyboardView(this) { text -> currentInputConnection?.commitText(text, 1) }

    // Landscape would otherwise hand the whole screen to an extract view.
    override fun onEvaluateFullscreenMode(): Boolean = false
}
