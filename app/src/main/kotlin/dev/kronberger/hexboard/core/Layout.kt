package dev.kronberger.hexboard.core

sealed interface KeyAction {
    data class Text(val text: String) : KeyAction
    data object Space : KeyAction
    data object Delete : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object Symbols : KeyAction
    data object Emoji : KeyAction
}

/** What one key, or one half of a split key, shows and does. */
data class Face(val label: String, val action: KeyAction)

/**
 * A key on one hex. A split key is still one button: tapping or swiping up
 * gives its [face], swiping down its [lower] face. A [bare] key is drawn as
 * its label only and may hang off the keyboard's edge, so it is left out
 * when fitting the grid to the screen.
 * [alternates] is the text a swipe in each direction types instead.
 */
data class Key(
    val pos: Axial,
    val face: Face,
    val lower: Face? = null,
    val bare: Boolean = false,
    val alternates: Map<Direction, String> = emptyMap(),
)

class Layout(val keys: List<Key>) {
    private val byPos = keys.associateBy { it.pos }

    init {
        require(byPos.size == keys.size) { "two keys share a cell" }
    }

    val cells: Set<Axial> get() = byPos.keys

    /** The cells the grid is fitted to: everything but bare keys. */
    val fitCells: List<Axial> get() = keys.filterNot { it.bare }.map { it.pos }

    operator fun get(pos: Axial): Key? = byPos[pos]

    /** The key under ([x], [y]), snapping to the nearest one. */
    fun keyAt(grid: HexGrid, x: Float, y: Float): Key? = grid.nearest(cells, x, y)?.let(::get)

    companion object {
        private const val EMPTY = "·"

        /**
         * One string per row, keys separated by spaces. Odd rows are drawn
         * half a hex to the right. `·` leaves a cell empty, `top/bottom`
         * makes a split key, and the tokens in [bare] become
         * label-only edge keys. `␣ ⌫ ⏎ ⇧ 123 😊` are the function keys; any
         * other token types itself. [alternates] gives swipe outputs per
         * token.
         */
        fun parse(
            rows: List<String>,
            bare: Set<String> = emptySet(),
            alternates: Map<String, Map<Direction, String>> = emptyMap(),
        ): Layout = Layout(
            rows.flatMapIndexed { row, line ->
                line.trim().split(Regex("\\s+")).mapIndexedNotNull { col, token ->
                    if (token == EMPTY) return@mapIndexedNotNull null
                    val halves = if (token.length > 1) token.split("/", limit = 2) else listOf(token)
                    Key(
                        pos = Axial.fromRowCol(row, col),
                        face = faceFor(halves[0]),
                        lower = halves.getOrNull(1)?.let(::faceFor),
                        bare = token in bare,
                        alternates = alternates[token].orEmpty(),
                    )
                }
            },
        )

        private fun faceFor(token: String) = Face(
            token,
            when (token) {
                "␣" -> KeyAction.Space
                "⌫" -> KeyAction.Delete
                "⏎" -> KeyAction.Enter
                "⇧" -> KeyAction.Shift
                "123" -> KeyAction.Symbols
                "😊" -> KeyAction.Emoji
                else -> KeyAction.Text(token)
            },
        )
    }
}

object Layouts {
    /**
     * Typewise's honeycomb, read off a screenshot of its German layout with
     * `y` and `z` swapped back to QWERTY places. The two space keys flank
     * `f h` in the middle row; shift and delete hang off the screen edges
     * beside them.
     */
    val english = Layout.parse(
        listOf(
            "· w e t y i o",
            "q a r g u l p",
            "⇧ ,/. ␣ f h ␣ !/? ⌫",
            "z s d n m j k",
            "· x c v b 😊/123 ⏎",
        ),
        bare = setOf("⇧", "⌫"),
        // Diagonals only: on split keys the top and bottom labels already
        // occupy the up and down positions.
        alternates = mapOf(
            ",/." to mapOf(
                Direction.UP_LEFT to "\"",
                Direction.UP_RIGHT to "'",
                Direction.DOWN_LEFT to ";",
                Direction.DOWN_RIGHT to ":",
            ),
            "!/?" to mapOf(
                Direction.UP_LEFT to "(",
                Direction.UP_RIGHT to ")",
                Direction.DOWN_LEFT to "-",
                Direction.DOWN_RIGHT to "@",
            ),
        ),
    )
}
