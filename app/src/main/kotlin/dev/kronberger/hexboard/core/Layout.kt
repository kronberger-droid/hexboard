package dev.kronberger.hexboard.core

sealed interface KeyAction {
    data class Text(val text: String) : KeyAction
    data object Space : KeyAction
    data object Delete : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object Symbols : KeyAction
    data object Letters : KeyAction
    data object Emoji : KeyAction

    /** Show [text] provisionally where the next character goes, replacing what was shown; null takes it away. */
    data class Preview(val text: String?) : KeyAction

    // Sideways drags; no layout token produces these.

    /**
     * Move [drag] on by [delta] grapheme clusters, or by [delta] words if
     * [words]; see [Drag] for the sign.
     */
    data class DragBy(val drag: Drag, val delta: Int, val words: Boolean = false) : KeyAction

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
 * with a [sideways] drag reads up, down, left and right, plus a narrower
 * band for each diagonal it has an alternate for. A key without one, a
 * fixed key, reads six equal directions instead.
 * [longPress] is the text a finger resting on the key, or swiping it
 * up-right, types instead. A key that [repeats] keeps tapping while a
 * finger rests on it.
 */
data class Key(
    val pos: Axial,
    val face: Face,
    val lower: Face? = null,
    val bare: Boolean = false,
    val alternates: Map<Direction, String> = emptyMap(),
    val sideways: Sideways? = Sideways.EDIT,
    val longPress: String? = null,
    val repeats: Boolean = face.action == KeyAction.Delete,
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
        private val MODIFIERS = mapOf(":select" to Sideways.SELECT, ":move" to Sideways.MOVE, ":fixed" to null)

        /**
         * One string per row, keys separated by spaces. Odd rows are drawn
         * half a hex to the right. `·` leaves a cell empty, `top/bottom`
         * makes a split key, and the tokens in [bare] and the keys in
         * [bareCells] become label-only edge keys. `␣ ⌫ ⏎ ⇧ 123 abc 😊` are the function keys; any
         * other token types itself. [alternates] gives swipe outputs per
         * token and [longPress] the text a long press types, looked up by the
         * whole token first and then without its suffix. A `:select` or
         * `:move` suffix sets the key's sideways drag; `:fixed` takes it away.
         */
        fun parse(
            rows: List<String>,
            bare: Set<String> = emptySet(),
            bareCells: Set<Axial> = emptySet(),
            alternates: Map<String, Map<Direction, String>> = emptyMap(),
            longPress: Map<String, String> = emptyMap(),
        ): Layout = Layout(
            rows.flatMapIndexed { row, line ->
                line.trim().split(Regex("\\s+")).mapIndexedNotNull { col, token ->
                    if (token == EMPTY) return@mapIndexedNotNull null
                    val modifier = MODIFIERS.keys.firstOrNull { token.endsWith(it) && token.length > it.length }
                    val base = token.removeSuffix(modifier.orEmpty())
                    val halves = if (base.length > 1) base.split("/", limit = 2) else listOf(base)
                    val alts = alternates[token] ?: alternates[base].orEmpty()
                    val pos = Axial.fromRowCol(row, col)
                    Key(
                        pos = pos,
                        face = faceFor(halves[0]),
                        lower = halves.getOrNull(1)?.let(::faceFor),
                        bare = token in bare || pos in bareCells,
                        alternates = alts,
                        sideways = if (modifier != null) MODIFIERS.getValue(modifier) else Sideways.EDIT,
                        longPress = longPress[token] ?: longPress[base],
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
                "abc", "ABC" -> KeyAction.Letters
                "😊" -> KeyAction.Emoji
                else -> KeyAction.Text(token)
            },
        )
    }
}

/** The built-in layers, from the default keymap. */
object Layouts {
    private val default = Keymaps.parse(Presets.DEFAULT.text)

    val english: Layout = default.letters
    val symbols: Layout = default.symbols
}
