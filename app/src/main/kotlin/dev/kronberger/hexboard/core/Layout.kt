package dev.kronberger.hexboard.core

sealed interface KeyAction {
    data class Text(val text: String) : KeyAction
    data object Space : KeyAction
    data object Delete : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object Symbols : KeyAction
    data object Emoji : KeyAction

    // Sideways drags; no layout token produces these.

    /** Move [drag] on by [delta] grapheme clusters; see [Drag] for the sign. */
    data class DragBy(val drag: Drag, val delta: Int) : KeyAction

    /** The finger lifted ([keep]) or the gesture was taken away (not [keep]). */
    data class DragEnd(val drag: Drag, val keep: Boolean = true) : KeyAction
}

/** What a sideways drag in progress does. A positive delta means: */
enum class Drag {
    /** select one more cluster before the cursor, deleted on release; */
    SCRUB,

    /** bring back one more cluster of the last deletion; */
    RECALL,

    /** move the cursor one cluster right; */
    MOVE,

    /** move the selection's moving end one cluster right. */
    SELECT,
}

/** What dragging sideways on a key does. */
enum class Sideways {
    /** Left scrubs, right recalls. */
    EDIT,

    /** Either way moves the cursor. */
    MOVE,

    /** Either way extends a selection from the cursor. */
    SELECT,
}

/** What one key, or one half of a split key, shows and does. */
data class Face(val label: String, val action: KeyAction)

/**
 * A key on one hex. A split key is still one button: swiping up gives its
 * [face], tapping or swiping down its [lower] face. A [bare] key is drawn as
 * its label only and may hang off the keyboard's edge, so it is left out
 * when fitting the grid to the screen.
 * [alternates] is the text a swipe in each direction types instead. A key
 * with alternates reads six swipe directions and has no [sideways] drag;
 * every other key reads four, with left and right given by [sideways].
 */
data class Key(
    val pos: Axial,
    val face: Face,
    val lower: Face? = null,
    val bare: Boolean = false,
    val alternates: Map<Direction, String> = emptyMap(),
    val sideways: Sideways? = if (alternates.isEmpty()) Sideways.EDIT else null,
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
        private val MODIFIERS = mapOf(":select" to Sideways.SELECT, ":move" to Sideways.MOVE)

        /**
         * One string per row, keys separated by spaces. Odd rows are drawn
         * half a hex to the right. `·` leaves a cell empty, `top/bottom`
         * makes a split key, and the tokens in [bare] become
         * label-only edge keys. `␣ ⌫ ⏎ ⇧ 123 😊` are the function keys; any
         * other token types itself. [alternates] gives swipe outputs per
         * token. A `:select` or `:move` suffix sets the key's sideways drag.
         */
        fun parse(
            rows: List<String>,
            bare: Set<String> = emptySet(),
            alternates: Map<String, Map<Direction, String>> = emptyMap(),
        ): Layout = Layout(
            rows.flatMapIndexed { row, line ->
                line.trim().split(Regex("\\s+")).mapIndexedNotNull { col, token ->
                    if (token == EMPTY) return@mapIndexedNotNull null
                    val modifier = MODIFIERS.keys.firstOrNull { token.endsWith(it) && token.length > it.length }
                    val base = token.removeSuffix(modifier.orEmpty())
                    val halves = if (base.length > 1) base.split("/", limit = 2) else listOf(base)
                    val alts = alternates[token].orEmpty()
                    Key(
                        pos = Axial.fromRowCol(row, col),
                        face = faceFor(halves[0]),
                        lower = halves.getOrNull(1)?.let(::faceFor),
                        bare = token in bare,
                        alternates = alts,
                        sideways = when {
                            alts.isNotEmpty() -> null
                            modifier != null -> MODIFIERS.getValue(modifier)
                            else -> Sideways.EDIT
                        },
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
     * beside them. Dragging the left space selects, the right one moves the
     * cursor.
     */
    val english = Layout.parse(
        listOf(
            "· w e t y i o",
            "q a r g u l p",
            "⇧ ,/. ␣:select f h ␣:move !/? ⌫",
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
