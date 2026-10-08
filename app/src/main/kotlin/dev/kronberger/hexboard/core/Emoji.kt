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

/** The five Fitzpatrick skin tone modifiers, light to dark. */
val SKIN_TONES: List<String> = (0x1F3FB..0x1F3FF).map { String(Character.toChars(it)) }

/**
 * [emoji] in skin [tone]: the modifier goes right after the first code
 * point, replacing a variation selector there, which is where Unicode puts
 * it for single people and for the first person of a ZWJ sequence. Whether
 * the result is a real emoji is for the font to say.
 */
fun withTone(emoji: String, tone: String): String {
    val first = Character.charCount(emoji.codePointAt(0))
    val rest = emoji.substring(first).removePrefix("\uFE0F")
    return emoji.substring(0, first) + tone + rest
}

/** [emoji] with any skin tone taken out, e.g. a toned one from the recents. */
fun withoutTone(emoji: String): String = SKIN_TONES.fold(emoji) { e, tone -> e.replace(tone, "") }
