package dev.kronberger.hexboard.core

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * Turns gestures on keys into actions, keeping the keyboard's state in
 * between: shift, and whether [letters] or [symbols] is showing.
 */
class Keyboard(private val letters: Layout, private val symbols: Layout = letters) {

    var shift = ShiftState.OFF
        private set

    var showingSymbols = false

    val layout: Layout get() = if (showingSymbols) symbols else letters

    /** What the enter key says, set from the editor; null keeps its own label. */
    var enterLabel: String? = null

    /**
     * The action for [gesture] starting on [key], or null when the gesture
     * only changes keyboard state.
     *
     * A swipe uses the key's alternate for its direction if there is one.
     * Otherwise, on a split key, up picks the upper face and everything else
     * the lower one, which is the default. On a plain key, up forces a
     * capital and down forces lowercase. Any other direction falls back to a
     * tap, so a tap that slid does not get lost. A hold types the key's
     * long-press text, following shift like a tap.
     */
    fun resolve(key: Key, gesture: Gesture): KeyAction? = when (val action = action(key, gesture)) {
        KeyAction.Shift -> {
            shift = when (shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            null
        }
        KeyAction.Symbols, KeyAction.Letters -> {
            showingSymbols = action == KeyAction.Symbols
            null
        }
        else -> action
    }

    private fun action(key: Key, gesture: Gesture): KeyAction {
        val face = key.face
        if (gesture == Gesture.Hold) key.longPress?.let { return typed(KeyAction.Text(cased(it, shift != ShiftState.OFF))) }
        val direction = (gesture as? Gesture.Swipe)?.direction
        direction?.let { key.alternates[it] }?.let { return typed(KeyAction.Text(it)) }

        if (key.lower != null) {
            val picked = if (direction == Direction.UP) face else key.lower
            return if (picked.action is KeyAction.Text) typed(picked.action) else picked.action
        }

        val action = face.action as? KeyAction.Text ?: return face.action
        val upper = when (direction) {
            Direction.UP -> true
            Direction.DOWN -> false
            else -> shift != ShiftState.OFF
        }
        return typed(KeyAction.Text(cased(action.text, upper)))
    }

    private fun cased(text: String, upper: Boolean) = if (upper) text.uppercase() else text

    /** What [face] shows in the current state. */
    fun label(face: Face): String = when {
        face.action == KeyAction.Enter -> enterLabel ?: face.label
        face.action is KeyAction.Text && shift != ShiftState.OFF -> face.label.uppercase()
        else -> face.label
    }

    /** A one-shot shift lasts for exactly one typed character. */
    private fun typed(action: KeyAction): KeyAction {
        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
        return action
    }
}
