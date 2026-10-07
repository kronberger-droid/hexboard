package dev.kronberger.hexboard.core

enum class ShiftState { OFF, ONCE, LOCKED }

/** Turns gestures on keys into actions, keeping the shift state in between. */
class Keyboard(val layout: Layout) {

    var shift = ShiftState.OFF
        private set

    /**
     * The action for [gesture] starting on [face] of [key], or null when the
     * gesture only changes keyboard state.
     *
     * A swipe uses the key's alternate for its direction if there is one.
     * Otherwise up forces a capital, down forces lowercase, and any other
     * direction falls back to a tap, so a tap that slid does not get lost.
     */
    fun resolve(key: Key, face: Face, gesture: Gesture): KeyAction? {
        if (face.action == KeyAction.Shift) {
            shift = when (shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            return null
        }
        val direction = (gesture as? Gesture.Swipe)?.direction
        direction?.let { key.alternates[it] }?.let { return typed(KeyAction.Text(it)) }

        val action = face.action as? KeyAction.Text ?: return face.action
        val upper = when (direction) {
            Direction.UP -> true
            Direction.DOWN -> false
            else -> shift != ShiftState.OFF
        }
        return typed(KeyAction.Text(if (upper) action.text.uppercase() else action.text))
    }

    /** What [face] shows in the current shift state. */
    fun label(face: Face): String =
        if (face.action is KeyAction.Text && shift != ShiftState.OFF) face.label.uppercase() else face.label

    /** A one-shot shift lasts for exactly one typed character. */
    private fun typed(action: KeyAction): KeyAction {
        if (shift == ShiftState.ONCE) shift = ShiftState.OFF
        return action
    }
}
