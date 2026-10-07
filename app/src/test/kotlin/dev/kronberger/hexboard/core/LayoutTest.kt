package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutTest {

    private fun textOf(face: Face?) = (face?.action as? KeyAction.Text)?.text

    @Test
    fun tokensBecomeKeysAtTheirRowAndColumn() {
        val layout = Layout.parse(listOf("a b", "c ⌫"))
        assertEquals("b", textOf(layout[Axial.fromRowCol(0, 1)]?.face))
        assertEquals(KeyAction.Delete, layout[Axial.fromRowCol(1, 1)]?.face?.action)
    }

    @Test
    fun specialTokensMapToActions() {
        val actions = Layout.parse(listOf("␣ ⌫ ⏎ ⇧ 123 😊 x")).keys.map { it.face.action }
        assertEquals(
            listOf(
                KeyAction.Space, KeyAction.Delete, KeyAction.Enter, KeyAction.Shift,
                KeyAction.Symbols, KeyAction.Emoji, KeyAction.Text("x"),
            ),
            actions,
        )
    }

    @Test
    fun dotLeavesTheCellEmpty() {
        val layout = Layout.parse(listOf("a · c"))
        assertNull(layout[Axial.fromRowCol(0, 1)])
        assertEquals(2, layout.keys.size)
    }

    @Test
    fun slashSplitsAKeyButALoneSlashTypesItself() {
        val keys = Layout.parse(listOf(",/. /")).keys
        assertEquals(",", textOf(keys[0].face))
        assertEquals(".", textOf(keys[0].lower))
        assertEquals("/", textOf(keys[1].face))
        assertNull(keys[1].lower)
    }

    @Test
    fun splitKeyTopAndBottomHalvesTypeDifferentFaces() {
        val layout = Layout.parse(listOf("!/?"))
        val grid = HexGrid(radius = 10f, originX = 0f, originY = 0f)
        assertEquals("!", textOf(layout.faceAt(grid, 0f, -5f)))
        assertEquals("?", textOf(layout.faceAt(grid, 0f, 5f)))
    }

    @Test
    fun bareKeysAreLeftOutOfFitting() {
        val layout = Layout.parse(listOf("⇧ a ⌫"), bare = setOf("⇧", "⌫"))
        assertEquals(listOf(Axial.fromRowCol(0, 1)), layout.fitCells)
        assertEquals(3, layout.cells.size)
    }

    @Test
    fun englishHasEveryLetterOnce() {
        val letters = Layouts.english.keys.mapNotNull { textOf(it.face) }.filter { it.single().isLetter() }
        assertEquals(('a'..'z').map { it.toString() }, letters.sorted())
        assertFalse(Layouts.english.keys.any { it.bare && it.pos in Layouts.english.fitCells })
    }
}
