package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiTest {

    @Test
    fun parsesGroupsAndSkipsComments() {
        val groups = parseEmojiAsset("# header\nSmileys\t😀 😃\n\nFlags\t🏁 🇦🇹\n")
        assertEquals(listOf(EmojiGroup("Smileys", listOf("😀", "😃")), EmojiGroup("Flags", listOf("🏁", "🇦🇹"))), groups)
    }

    @Test
    fun keepsZwjSequencesWhole() {
        val groups = parseEmojiAsset("People\t👨‍👩‍👧 👋\n")
        assertEquals(listOf("👨‍👩‍👧", "👋"), groups.single().emoji)
    }

    @Test
    fun theCommittedAssetParsesWithoutSkinTones() {
        val text = File("src/main/assets/emoji.txt").readText()
        val groups = parseEmojiAsset(text)
        assertEquals("Smileys & Emotion", groups.first().name)
        val all = groups.flatMap { it.emoji }
        assertTrue(all.size > 1500)
        assertTrue(all.none { e -> e.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF } })
        assertTrue(groups.none { it.name == "Component" })
    }

    @Test
    fun recentsMoveToTheFrontWithoutDuplicates() {
        val r = Recents(max = 3)
        r.used("a")
        r.used("b")
        r.used("a")
        assertEquals(listOf("a", "b"), r.emoji)
        r.used("c")
        r.used("d")
        assertEquals(listOf("d", "c", "a"), r.emoji)
    }

    @Test
    fun recentsSurviveStorage() {
        val r = Recents()
        r.used("👨‍👩‍👧")
        r.used("🇦🇹")
        assertEquals(listOf("🇦🇹", "👨‍👩‍👧"), Recents.parse(r.serialize()).emoji)
        assertEquals(emptyList<String>(), Recents.parse(null).emoji)
    }

    @Test
    fun gridHasAHeaderPerSectionThenFullRows() {
        val grid = EmojiGrid(listOf(EmojiGroup("A", listOf("1", "2", "3")), EmojiGroup("B", listOf("4"))), columns = 2)
        assertEquals(
            listOf(
                EmojiGrid.Row.Header(0, "A"),
                EmojiGrid.Row.Emojis(0, listOf("1", "2")),
                EmojiGrid.Row.Emojis(0, listOf("3")),
                EmojiGrid.Row.Header(1, "B"),
                EmojiGrid.Row.Emojis(1, listOf("4")),
            ),
            grid.rows,
        )
        assertEquals(3, grid.firstRowOf(1))
        assertEquals(1, grid.sectionAt(4))
    }

    @Test
    fun gridHitTestingSkipsHeadersAndEmptyCells() {
        val grid = EmojiGrid(listOf(EmojiGroup("A", listOf("1", "2", "3"))), columns = 2)
        assertNull(grid.at(0, 0))
        assertEquals("2", grid.at(1, 1))
        assertNull(grid.at(2, 1))
        assertNull(grid.at(9, 0))
    }

    @Test
    fun toneGoesAfterTheFirstCodePointAndReplacesAVariationSelector() {
        val light = SKIN_TONES.first()
        assertEquals("👋🏻", withTone("👋", light))
        assertEquals("✌🏻", withTone("✌️", light))
        assertEquals("🧔🏻\u200D♂️", withTone("🧔\u200D♂️", light))
        assertEquals("👨🏿\u200D🦰", withTone("👨\u200D🦰", SKIN_TONES.last()))
    }
}
