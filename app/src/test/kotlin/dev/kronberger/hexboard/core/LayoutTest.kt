package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun suffixSetsTheSidewaysDragAndKeepsTheKeyItself() {
        val keys = Layout.parse(listOf("␣:select ␣:move ␣ a")).keys
        assertEquals(listOf(Sideways.SELECT, Sideways.MOVE, Sideways.EDIT, Sideways.EDIT), keys.map { it.sideways })
        assertEquals(KeyAction.Space, keys[0].face.action)
        assertEquals("␣", keys[1].face.label)
    }

    @Test
    fun symbolsHaveEveryDigitOnceAndAWayBack() {
        val texts = Layouts.symbols.keys.mapNotNull { textOf(it.face) }
        assertEquals(('0'..'9').map { it.toString() }, texts.filter { it.single().isDigit() }.sorted())
        assertTrue(Layouts.symbols.keys.any { it.lower?.action == KeyAction.Letters })
    }

    @Test
    fun layersShareTheirShapeSoSwitchingKeepsTheHeight() {
        assertEquals(Layouts.english.fitCells.toSet(), Layouts.symbols.fitCells.toSet())
    }

    @Test
    fun keysWithAlternatesHaveNoSidewaysDrag() {
        val key = Layout.parse(listOf(",/."), alternates = mapOf(",/." to mapOf(Direction.UP_LEFT to "\""))).keys.single()
        assertNull(key.sideways)
    }

    @Test
    fun leftSpaceMovesRightSpaceEditsAndEnterSelectsOnBothLayers() {
        for (layout in listOf(Layouts.english, Layouts.symbols)) {
            val spaces = layout.keys.filter { it.face.action == KeyAction.Space }.sortedBy { it.pos.q }
            assertEquals(listOf(Sideways.MOVE, Sideways.EDIT), spaces.map { it.sideways })
            assertEquals(Sideways.SELECT, layout.keys.single { it.face.action == KeyAction.Enter }.sideways)
        }
    }

    @Test
    fun englishHasUmlautsOnLongPress() {
        val longPresses = Layouts.english.keys.mapNotNull { k -> k.longPress?.let { textOf(k.face) to it } }.toMap()
        assertEquals(mapOf("a" to "ä", "o" to "ö", "u" to "ü"), longPresses)
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
