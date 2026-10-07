package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutTest {

    @Test
    fun tokensBecomeKeysAtTheirRowAndColumn() {
        val layout = Layout.parse(listOf("a b", "c ⌫"))
        assertEquals(KeyAction.Text("b"), layout[Axial.fromRowCol(0, 1)]?.action)
        assertEquals(KeyAction.Delete, layout[Axial.fromRowCol(1, 1)]?.action)
        assertEquals(2, layout.rows)
    }

    @Test
    fun specialTokensMapToActions() {
        val actions = Layout.parse(listOf("␣ ⌫ ⏎ x")).keys.map { it.action }
        assertEquals(
            listOf(KeyAction.Space, KeyAction.Delete, KeyAction.Enter, KeyAction.Text("x")),
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
    fun englishHasEveryLetterOnce() {
        val letters = Layouts.english.keys.mapNotNull { (it.action as? KeyAction.Text)?.text }
        assertEquals(('a'..'z').map { it.toString() }, letters.sorted())
    }
}
