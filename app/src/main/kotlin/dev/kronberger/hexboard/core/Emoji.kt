package dev.kronberger.hexboard.core

data class EmojiGroup(val name: String, val emoji: List<String>)

/**
 * Parse `assets/emoji.txt` as written by `scripts/emoji.nu`: one group per
 * line, its name, a tab, then its emoji separated by spaces. Lines starting
 * with `#` are comments.
 */
fun parseEmojiAsset(text: String): List<EmojiGroup> = text.lineSequence()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .mapNotNull { line ->
        val (name, list) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        EmojiGroup(name, list.split(' ').filter { it.isNotEmpty() })
    }
    .toList()

/** Recently used emoji, most recent first, at most [max]. */
class Recents(private val max: Int = 32, initial: List<String> = emptyList()) {
    var emoji: List<String> = initial.distinct().take(max)
        private set

    fun used(e: String) {
        emoji = (listOf(e) + emoji.filter { it != e }).take(max)
    }

    /** For storage; no emoji sequence contains a space. */
    fun serialize(): String = emoji.joinToString(" ")

    companion object {
        fun parse(stored: String?, max: Int = 32) =
            Recents(max, stored.orEmpty().split(' ').filter { it.isNotEmpty() })
    }
}

/**
 * The emoji panel's content as equal-height rows: each section opens with a
 * header row naming it, followed by its emoji [columns] to a row.
 */
class EmojiGrid(val sections: List<EmojiGroup>, val columns: Int) {

    sealed interface Row {
        val section: Int

        data class Header(override val section: Int, val title: String) : Row
        data class Emojis(override val section: Int, val emoji: List<String>) : Row
    }

    val rows: List<Row> = sections.flatMapIndexed { i, group ->
        listOf(Row.Header(i, group.name)) + group.emoji.chunked(columns).map { Row.Emojis(i, it) }
    }

    private val firstRows = sections.indices.map { i -> rows.indexOfFirst { it.section == i } }

    /** The header row of section [i]. */
    fun firstRowOf(i: Int): Int = firstRows[i]

    /** The section row [row] belongs to. */
    fun sectionAt(row: Int): Int = rows.getOrNull(row.coerceIn(0, rows.size - 1))?.section ?: 0

    /** The emoji at [row], [column], or null for headers and empty cells. */
    fun at(row: Int, column: Int): String? = (rows.getOrNull(row) as? Row.Emojis)?.emoji?.getOrNull(column)
}
