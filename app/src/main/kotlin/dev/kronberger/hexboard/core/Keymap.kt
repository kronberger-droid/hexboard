package dev.kronberger.hexboard.core

/** The two layers a keymap defines. */
class Keymap(val letters: Layout, val symbols: Layout)

/** What is wrong with a keymap text, and on which line, counted from 1. */
class KeymapError(val line: Int, val reason: String) : Exception("line $line: $reason")

/**
 * Keymaps as text: a `[letters]` and a `[symbols]` section of five rows in
 * [Layout.parse]'s syntax, and a `[keys]` section adding long presses and
 * swipes to keys by their text. [Presets.DEFAULT] documents the format in
 * its own comments.
 *
 * Every layer keeps the shape of the built-in honeycomb, so switching
 * keymaps or layers never changes the keyboard's size.
 */
object Keymaps {

    /** Row lengths, empty cells and bare edge cells every layer keeps. */
    private val ROW_LENGTHS = listOf(7, 7, 8, 7, 7)
    private val EMPTY_CELLS = setOf(0 to 0, 4 to 0)
    private val BARE_CELLS = setOf(2 to 0, 2 to 7)

    /** Words for the keys this keyboard cannot type into the editor itself. */
    private val ALIASES = mapOf(
        "empty" to "·",
        "space" to "␣",
        "del" to "⌫",
        "enter" to "⏎",
        "shift" to "⇧",
        "emoji" to "😊",
        "sym" to "123",
        "hash" to "#",
    )

    private val SUFFIXES = listOf(":move", ":select", ":fixed")

    private val DIRECTIONS = mapOf(
        "up" to Direction.UP,
        "up-right" to Direction.UP_RIGHT,
        "down-right" to Direction.DOWN_RIGHT,
        "down" to Direction.DOWN,
        "down-left" to Direction.DOWN_LEFT,
        "up-left" to Direction.UP_LEFT,
    )

    private class Section(val name: String, val firstLine: Int) {
        val lines = mutableListOf<Pair<Int, String>>()
    }

    /** Parse [text]; throws [KeymapError] for the first problem found. */
    fun parse(text: String): Keymap {
        val sections = mutableMapOf<String, Section>()
        var current: Section? = null
        text.lines().forEachIndexed { i, raw ->
            val n = i + 1
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("[") && line.endsWith("]") -> {
                    val name = line.substring(1, line.length - 1).trim()
                    if (name !in setOf("letters", "symbols", "keys")) throw KeymapError(n, "unknown section [$name]")
                    if (name in sections) throw KeymapError(n, "section [$name] given twice")
                    current = Section(name, n).also { sections[name] = it }
                }
                else -> (current ?: throw KeymapError(n, "text before the first section")).lines += n to line
            }
        }
        val letters = sections["letters"] ?: throw KeymapError(1, "no [letters] section")
        val symbols = sections["symbols"] ?: throw KeymapError(1, "no [symbols] section")
        val rows = mapOf("letters" to rows(letters), "symbols" to rows(symbols))

        val alternates = mutableMapOf<String, MutableMap<Direction, String>>()
        val longPress = mutableMapOf<String, String>()
        sections["keys"]?.lines?.forEach { (n, line) -> addOns(n, line, rows.values.flatten().flatten(), alternates, longPress) }

        return Keymap(
            layer(letters, rows.getValue("letters"), alternates, longPress, KeyAction.Symbols, "123"),
            layer(symbols, rows.getValue("symbols"), alternates, longPress, KeyAction.Letters, "abc"),
        )
    }

    /** A token with aliases replaced, in each half of a split key. */
    private fun expand(token: String): String {
        val suffix = SUFFIXES.firstOrNull { token.endsWith(it) && token.length > it.length }.orEmpty()
        val base = token.removeSuffix(suffix)
        ALIASES[base]?.let { return it + suffix }
        val halves = if (base.length > 1 && "/" in base) base.split("/", limit = 2) else return token
        return halves.joinToString("/") { ALIASES[it] ?: it } + suffix
    }

    /** A token without its suffix. */
    private fun base(token: String) = SUFFIXES.fold(token) { t, s -> t.removeSuffix(s) }

    /** The section's rows as expanded tokens, checked against the shape. */
    private fun rows(section: Section): List<List<String>> {
        if (section.lines.size != ROW_LENGTHS.size) {
            throw KeymapError(section.firstLine, "[${section.name}] needs ${ROW_LENGTHS.size} rows, has ${section.lines.size}")
        }
        return section.lines.mapIndexed { row, (n, line) ->
            val tokens = line.split(Regex("\\s+")).map(::expand)
            if (tokens.size != ROW_LENGTHS[row]) {
                throw KeymapError(n, "row ${row + 1} needs ${ROW_LENGTHS[row]} keys, has ${tokens.size}")
            }
            tokens.forEachIndexed { col, token ->
                val shouldBeEmpty = (row to col) in EMPTY_CELLS
                if (shouldBeEmpty && token != "·") throw KeymapError(n, "key ${col + 1} of row ${row + 1} stays empty: ·")
                if (!shouldBeEmpty && token == "·") throw KeymapError(n, "key ${col + 1} of row ${row + 1} cannot be empty")
            }
            tokens
        }
    }

    /** One `[keys]` line: a key's text, then `hold=` and swipe directions. */
    private fun addOns(
        n: Int,
        line: String,
        tokens: List<String>,
        alternates: MutableMap<String, MutableMap<Direction, String>>,
        longPress: MutableMap<String, String>,
    ) {
        val parts = line.split(Regex("\\s+"))
        val target = expand(parts[0])
        val matches = tokens.filter { it == target }.ifEmpty { tokens.filter { base(it) == target } }.distinct()
        if (matches.isEmpty()) throw KeymapError(n, "no key ${parts[0]} in either layer")
        if (parts.size == 1) throw KeymapError(n, "nothing to add to ${parts[0]}")
        for (part in parts.drop(1)) {
            val (name, value) = part.split("=", limit = 2).takeIf { it.size == 2 && it[1].isNotEmpty() }
                ?: throw KeymapError(n, "expected name=text, got $part")
            for (token in matches) {
                val split = base(token).let { it.length > 1 && "/" in it }
                when {
                    name == "hold" -> {
                        if (base(token) == "⌫") throw KeymapError(n, "⌫ repeats while held, so it takes no hold=")
                        longPress[token] = value
                    }
                    name in DIRECTIONS -> {
                        val direction = DIRECTIONS.getValue(name)
                        if (split && (direction == Direction.UP || direction == Direction.DOWN)) {
                            throw KeymapError(n, "${parts[0]} is split; its halves already own up and down")
                        }
                        alternates.getOrPut(token) { mutableMapOf() }[direction] = value
                    }
                    else -> throw KeymapError(n, "unknown $name=; use hold or ${DIRECTIONS.keys.joinToString(" ")}")
                }
            }
        }
    }

    /** Build a layer and check it has the keys a layer cannot do without. */
    private fun layer(
        section: Section,
        rows: List<List<String>>,
        alternates: Map<String, Map<Direction, String>>,
        longPress: Map<String, String>,
        switch: KeyAction,
        switchToken: String,
    ): Layout {
        val bare = BARE_CELLS.map { (row, col) -> Axial.fromRowCol(row, col) }.toSet()
        val layout = Layout.parse(rows.map { it.joinToString(" ") }, bareCells = bare, alternates = alternates, longPress = longPress)
        val actions = layout.keys.flatMap { listOfNotNull(it.face.action, it.lower?.action) }.toSet()
        for ((action, token) in listOf(KeyAction.Delete to "⌫", KeyAction.Enter to "⏎", KeyAction.Space to "␣", switch to switchToken)) {
            if (action !in actions) throw KeymapError(section.firstLine, "[${section.name}] has no $token key")
        }
        return layout
    }
}

/** A keymap that ships with the keyboard. */
class Preset(val name: String, val text: String)

object Presets {
    private const val HELP = """# Hexboard keymap. Lines starting with # are comments.
#
# [letters] and [symbols] hold five rows of keys each, separated by
# spaces; odd rows sit half a key to the right. Keep each row's length
# and the empty cells (· or the word empty) where they are, so the
# keyboard keeps its size. The first and last key of the middle row hang
# off the screen edges.
#
#   a          types a; any text works
#   ,/.        a split key: swipe up for the top half, tap for the bottom
#   ␣ ⌫ ⏎ ⇧    space, delete, enter, shift; or write space del enter shift
#   😊/123     emoji panel and symbols; or emoji/sym. abc goes back to letters
#   :move      after a key: drag sideways to move the cursor
#   :select    drag sideways to select
#   :fixed     no drag; swipes read six equal directions instead
#   (nothing)  drag left to delete, right to bring it back
#
# [keys] adds to keys by their text, in both layers, e.g.
#   a hold=ä up-left=á down-left=à
# hold= is typed on a long press and on a swipe up-right. Swipes go up,
# down, up-left, up-right, down-left, down-right. On a key with a drag a
# diagonal takes a narrow band and the drag stays. On plain letters up and
# down are capitals and lowercase unless set here. Write hash for the #
# key, since a line starting with # is a comment.
"""

    private const val SYMBOLS = """[symbols]
· ~/^ 1 2 3 4 >/<:fixed
$/€:fixed =/+ 5 6 7 [/(:fixed ]/):fixed
⇧ ,/.:fixed ␣:move 8 9 ␣ !/?:fixed ⌫
°/§ –/- _ 0 `/* |//:fixed ¡/¿
· & % @ # 😊/ABC ⏎:select
"""

    private const val PUNCTUATION = """,/. up-left=" up-right=' down-left=; down-right=:
!/? up-left=( up-right=) down-left=- down-right=@
>/< up-right=» down-right=«
$/€ down-right=£
[/( down-right={
]/) down-right=}
|// down-right=\
"""

    private const val UMLAUTS = """a hold=ä
o hold=ö
u hold=ü
s hold=ß
"""

    private fun letters(top: String, bottom: String) = """[letters]
$top
q a r g u l p
⇧ ,/.:fixed ␣:move f h ␣ !/?:fixed ⌫
$bottom
· x c v b 😊/123 ⏎:select
"""

    /** Typewise's honeycomb with QWERTY's y and z, and German letters on a long press. */
    val DEFAULT = Preset(
        "English with umlauts",
        HELP + "\n" + letters("· w e t y i o", "z s d n m j k") + "\n" + SYMBOLS + "\n[keys]\n" + UMLAUTS + PUNCTUATION,
    )

    /** Typewise's German honeycomb as it ships: z top, y bottom. */
    val GERMAN = Preset(
        "German (QWERTZ)",
        HELP + "\n" + letters("· w e t z i o", "y s d n m j k") + "\n" + SYMBOLS + "\n[keys]\n" + UMLAUTS + PUNCTUATION,
    )

    val ENGLISH = Preset(
        "English",
        HELP + "\n" + letters("· w e t y i o", "z s d n m j k") + "\n" + SYMBOLS + "\n[keys]\n" + PUNCTUATION,
    )

    val all = listOf(DEFAULT, GERMAN, ENGLISH)
}
