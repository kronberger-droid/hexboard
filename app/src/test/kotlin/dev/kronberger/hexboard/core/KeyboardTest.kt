package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {

    private val layout = Layout.parse(
        listOf("a ⇧ ,/. ␣"),
        alternates = mapOf(",/." to mapOf(Direction.DOWN_RIGHT to ":")),
        longPress = mapOf("a" to "ä"),
    )
    private val a = layout.keys[0]
    private val shiftKey = layout.keys[1]
    private val punct = layout.keys[2]
    private val space = layout.keys[3]

    private fun Keyboard.press(key: Key, gesture: Gesture = Gesture.Tap) = resolve(key, gesture)

    private fun text(s: String) = KeyAction.Text(s)
    private fun swipe(d: Direction) = Gesture.Swipe(d)

    @Test
    fun tapTypesLowercase() {
        assertEquals(text("a"), Keyboard(layout).press(a))
    }

    @Test
    fun holdTypesTheLongPressAndFollowsShift() {
        val kb = Keyboard(layout)
        assertEquals(text("ä"), kb.press(a, Gesture.Hold))
        kb.press(shiftKey)
        assertEquals(text("Ä"), kb.press(a, Gesture.Hold))
        assertEquals(ShiftState.OFF, kb.shift)
    }

    @Test
    fun shiftedEszettStaysOneLetter() {
        val s = Layout.parse(listOf("s ⇧"), longPress = mapOf("s" to "ß"))
        val kb = Keyboard(s)
        kb.press(s.keys[1])
        assertEquals(text("ß"), kb.press(s.keys[0], Gesture.Hold))
    }

    @Test
    fun holdOnAKeyWithoutALongPressIsATap() {
        assertEquals(KeyAction.Space, Keyboard(layout).press(space, Gesture.Hold))
    }

    @Test
    fun swipeUpTypesACapital() {
        assertEquals(text("A"), Keyboard(layout).press(a, swipe(Direction.UP)))
    }

    @Test
    fun shiftCyclesOffOnceLocked() {
        val kb = Keyboard(layout)
        assertNull(kb.press(shiftKey))
        assertEquals(ShiftState.ONCE, kb.shift)
        kb.press(shiftKey)
        assertEquals(ShiftState.LOCKED, kb.shift)
        kb.press(shiftKey)
        assertEquals(ShiftState.OFF, kb.shift)
    }

    @Test
    fun oneShotShiftCapitalisesOnlyTheNextCharacter() {
        val kb = Keyboard(layout)
        kb.press(shiftKey)
        assertEquals(text("A"), kb.press(a))
        assertEquals(text("a"), kb.press(a))
    }

    @Test
    fun oneShotShiftSurvivesSpace() {
        val kb = Keyboard(layout)
        kb.press(shiftKey)
        assertEquals(KeyAction.Space, kb.press(space))
        assertEquals(text("A"), kb.press(a))
    }

    @Test
    fun capsLockHoldsAndSwipeDownForcesLowercase() {
        val kb = Keyboard(layout)
        kb.press(shiftKey)
        kb.press(shiftKey)
        assertEquals(text("A"), kb.press(a))
        assertEquals(text("a"), kb.press(a, swipe(Direction.DOWN)))
        assertEquals(text("A"), kb.press(a))
        assertEquals(ShiftState.LOCKED, kb.shift)
    }

    @Test
    fun alternateWinsForItsDirection() {
        assertEquals(text(":"), Keyboard(layout).press(punct, swipe(Direction.DOWN_RIGHT)))
    }

    @Test
    fun unmappedSwipeFallsBackToATap() {
        val kb = Keyboard(layout)
        assertEquals(text("a"), kb.press(a, swipe(Direction.UP_LEFT)))
        assertEquals(text("."), kb.press(punct, swipe(Direction.UP_LEFT)))
    }

    @Test
    fun splitKeyIsOneButtonPickedByDirection() {
        val kb = Keyboard(layout)
        assertEquals(text("."), kb.press(punct))
        assertEquals(text(","), kb.press(punct, swipe(Direction.UP)))
        assertEquals(text("."), kb.press(punct, swipe(Direction.DOWN)))
    }

    @Test
    fun splitFunctionKeyGivesEmojiUpAndSymbolsDown() {
        val key = Layout.parse(listOf("😊/123")).keys.single()
        val kb = Keyboard(layout)
        assertEquals(KeyAction.Emoji, kb.press(key, swipe(Direction.UP)))
        assertNull(kb.press(key, swipe(Direction.DOWN)))
        assertTrue(kb.showingSymbols)
    }

    @Test
    fun functionKeySwitchesLayersBothWays() {
        val letters = Layout.parse(listOf("a 😊/123"))
        val symbols = Layout.parse(listOf("1 😊/abc"))
        val kb = Keyboard(letters, symbols)
        assertNull(kb.press(letters.keys[1]))
        assertEquals(symbols, kb.layout)
        assertEquals(text("1"), kb.press(symbols.keys[0]))
        assertNull(kb.press(symbols.keys[1]))
        assertEquals(letters, kb.layout)
    }

    @Test
    fun swipeUpOnTheFunctionKeyIsStillEmoji() {
        val letters = Layout.parse(listOf("😊/123"))
        val kb = Keyboard(letters, Layout.parse(listOf("x")))
        assertEquals(KeyAction.Emoji, kb.press(letters.keys[0], swipe(Direction.UP)))
        assertEquals(letters, kb.layout)
    }

    @Test
    fun enterShowsTheEditorsActionWhenSet() {
        val enter = Layout.parse(listOf("⏎")).keys.single().face
        val kb = Keyboard(layout)
        assertEquals("⏎", kb.label(enter))
        kb.enterLabel = "Send"
        assertEquals("Send", kb.label(enter))
    }

    @Test
    fun labelsFollowShift() {
        val kb = Keyboard(layout)
        assertEquals("a", kb.label(a.face))
        kb.press(shiftKey)
        assertEquals("A", kb.label(a.face))
        assertEquals(",", kb.label(punct.face))
    }
}
