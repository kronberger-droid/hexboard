package dev.kronberger.hexboard.core

enum class ShiftState { OFF, ONCE, LOCKED }

/** Turns gestures on keys into actions, keeping the shift state in between. */
class Keyboard(val layout: Layout) {

    var shift = ShiftState.OFF
        private set

    /**
     * The action for [gesture] starting on [key], or null when the gesture
     * only changes keyboard state.
     *
     * A swipe uses the key's alternate for its direction if there is one.
     * Otherwise, on a split key, down picks the lower face and everything
     * else the upper one. On a plain key, up forces a capital and down
     * forces lowercase. Any other direction falls back to a tap, so a tap
     * that slid does not get lost.
     */
    fun resolve(key: Key, gesture: Gesture): KeyAction? {
        val face = key.face
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

        if (key.lower != null) {
            val picked = if (direction == Direction.DOWN) key.lower else face
            return if (picked.action is KeyAction.Text) typed(picked.action) else picked.action
        }

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
