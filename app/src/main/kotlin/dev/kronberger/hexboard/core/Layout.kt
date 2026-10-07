package dev.kronberger.hexboard.core

sealed interface KeyAction {
    data class Text(val text: String) : KeyAction
    data object Space : KeyAction
    data object Delete : KeyAction
    data object Enter : KeyAction
}

data class Key(val pos: Axial, val label: String, val action: KeyAction)

class Layout(val keys: List<Key>) {
    private val byPos = keys.associateBy { it.pos }

    init {
        require(byPos.size == keys.size) { "two keys share a cell" }
    }

    val cells: Set<Axial> get() = byPos.keys
    val rows: Int get() = keys.maxOf { it.pos.r } - keys.minOf { it.pos.r } + 1

    operator fun get(pos: Axial): Key? = byPos[pos]

    companion object {
        private const val EMPTY = "·"

        /**
         * One string per row, keys separated by spaces. Odd rows are drawn
         * half a hex to the right. `·` leaves a cell empty; `␣`, `⌫` and `⏎`
         * are space, delete and enter; any other token types itself.
         */
        fun parse(rows: List<String>): Layout = Layout(
            rows.flatMapIndexed { row, line ->
                line.trim().split(Regex("\\s+")).mapIndexedNotNull { col, token ->
                    if (token == EMPTY) null else Key(Axial.fromRowCol(row, col), token, actionFor(token))
                }
            },
        )

        private fun actionFor(token: String): KeyAction = when (token) {
            "␣" -> KeyAction.Space
            "⌫" -> KeyAction.Delete
            "⏎" -> KeyAction.Enter
            else -> KeyAction.Text(token)
        }
    }
}

object Layouts {
    /**
     * A draft of Typewise's honeycomb: QWERTY letters, the two space keys in
     * the middle of the bottom row instead of a bar below the letters.
     */
    val english = Layout.parse(
        listOf(
            "q w e r t y u i o p",
            "a s d f g h j k l ⌫",
            "z x c v ␣ ␣ b n m ⏎",
        ),
    )
}
