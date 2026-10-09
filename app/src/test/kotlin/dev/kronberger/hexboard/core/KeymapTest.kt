package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeymapTest {

    private val default = Presets.DEFAULT.text

    private fun error(text: String): KeymapError =
        try {
            Keymaps.parse(text)
            fail("parsed")
            throw AssertionError()
        } catch (e: KeymapError) {
            e
        }

    private fun lineOf(text: String, needle: String) = text.lines().indexOfFirst { it.trim() == needle } + 1

    @Test
    fun defaultPresetIsTheBuiltInLayoutKeyForKey() {
        val parsed = Keymaps.parse(default)
        assertEquals(Golden.english.keys, parsed.letters.keys)
        assertEquals(Golden.symbols.keys, parsed.symbols.keys)
    }

    @Test
    fun writtenKeymapsReadBackToTheSameKeys() {
        for (preset in Presets.all) {
            val k = Keymaps.parse(preset.text)
            val again = Keymaps.parse(Keymaps.write(k))
            assertEquals(preset.name, k.letters.keys, again.letters.keys)
            assertEquals(preset.name, k.symbols.keys, again.symbols.keys)
        }
    }

    @Test
    fun addOnsGoToEveryKeyWithThatTextAndSurviveWriting() {
        val k = Keymaps.parse(default).withAddOns(",/.:fixed", "…", mapOf(Direction.UP_LEFT to "«"))
        for (layout in listOf(k.letters, k.symbols)) {
            val key = layout.keys.single { Keymaps.token(it) == ",/.:fixed" }
            assertEquals("…", key.longPress)
            assertEquals(mapOf(Direction.UP_LEFT to "«"), key.alternates)
        }
        assertEquals(k.letters.keys, Keymaps.parse(Keymaps.write(k)).letters.keys)
    }

    @Test
    fun swappingTradesTwoKeysAndKeepsTheEdgesBare() {
        val shift = Axial.fromRowCol(2, 0)
        val q = Axial.fromRowCol(1, 0)
        val k = Keymaps.parse(default).swapped(symbols = false, shift, q)
        assertEquals("q", k.letters[shift]?.face?.label)
        assertTrue(k.letters[shift]!!.bare)
        assertEquals(KeyAction.Shift, k.letters[q]?.face?.action)
        assertEquals(false, k.letters[q]!!.bare)
        // The symbols layer is untouched, and the result still writes and parses.
        assertEquals(Keymaps.parse(default).symbols.keys, k.symbols.keys)
        assertEquals(k.letters.keys, Keymaps.parse(Keymaps.write(k)).letters.keys)
    }

    @Test
    fun theHashKeyIsWrittenAsAWordSinceHashStartsAComment() {
        val k = Keymaps.parse(default).withAddOns("#", "№", emptyMap())
        val text = Keymaps.write(k)
        assertTrue(text.lines().contains("hash hold=№"))
        assertEquals("№", Keymaps.parse(text).symbols.keys.single { it.face.label == "#" }.longPress)
    }

    @Test
    fun everyPresetParsesInTheSameShape() {
        val cells = Layouts.english.cells
        for (preset in Presets.all) {
            val k = Keymaps.parse(preset.text)
            assertEquals(preset.name, cells, k.letters.cells)
            assertEquals(preset.name, cells, k.symbols.cells)
        }
    }

    @Test
    fun germanSwapsYAndZ() {
        val letters = Keymaps.parse(Presets.GERMAN.text).letters
        fun at(row: Int, col: Int) = letters[Axial.fromRowCol(row, col)]?.face?.label
        assertEquals("z", at(0, 4))
        assertEquals("y", at(3, 0))
    }

    @Test
    fun wordsStandForKeysThisKeyboardCannotType() {
        val words = default
            .replace("⇧ ,/.:fixed ␣:move f h ␣ !/?:fixed ⌫", "shift ,/.:fixed space:move f h space !/?:fixed del")
            .replace("· x c v b 😊/123 ⏎:select", "empty x c v b emoji/sym enter:select")
        assertEquals(Keymaps.parse(default).letters.keys, Keymaps.parse(words).letters.keys)
    }

    @Test
    fun keysLinesMatchTheWholeTokenFirst() {
        val text = default + "space hold=x\nspace:move hold=y\n"
        val spaces = Keymaps.parse(text).letters.keys.filter { it.face.action == KeyAction.Space }.sortedBy { it.pos.q }
        assertEquals(listOf("y", "x"), spaces.map { it.longPress })
    }

    @Test
    fun keysLinesAddDiagonalsToLettersAndKeepTheirDrag() {
        val e = Keymaps.parse(default + "e up-left=é down-left=è\n").letters.keys.single { it.face.label == "e" }
        assertEquals(mapOf(Direction.UP_LEFT to "é", Direction.DOWN_LEFT to "è"), e.alternates)
        assertEquals(Sideways.EDIT, e.sideways)
    }

    @Test
    fun rowsMustKeepTheirLength() {
        val bad = default.replace("q a r g u l p", "q a r g u l")
        val e = error(bad)
        assertEquals(lineOf(bad, "q a r g u l"), e.line)
        assertTrue(e.reason, "needs 7" in e.reason)
    }

    @Test
    fun emptyCellsStayPut() {
        assertTrue(error(default.replace("· w e t y i o", "v w e t y i o")).reason.contains("stays empty"))
        assertTrue(error(default.replace("q a r g u l p", "q a · g u l p")).reason.contains("cannot be empty"))
    }

    @Test
    fun aLayerNeedsItsFunctionKeys() {
        val noDelete = default.replace("⇧ ,/.:fixed ␣:move f h ␣ !/?:fixed ⌫", "⇧ ,/.:fixed ␣:move f h ␣ !/?:fixed x")
        assertTrue(error(noDelete).reason.contains("no ⌫"))
        val noWayBack = default.replace("· & % @ # 😊/ABC ⏎:select", "· & % @ # 😊 ⏎:select")
        assertTrue(error(noWayBack).reason.contains("no abc"))
    }

    @Test
    fun keysLinesAreChecked() {
        assertTrue(error(default + "ü hold=x\n").reason.contains("no key ü"))
        assertTrue(error(default + "a sideways=x\n").reason.contains("unknown sideways="))
        assertTrue(error(default + "del hold=x\n").reason.contains("repeats"))
        assertTrue(error(default + ",/. up=x\n").reason.contains("split"))
        assertTrue(error(default + "a hold\n").reason.contains("name=text"))
        val line = (default + "q nope\n").lines().size - 1
        assertEquals(line, error(default + "q nope\n").line)
    }

    @Test
    fun sectionsAreChecked() {
        assertTrue(error(default.replace("[symbols]", "[symbol]")).reason.contains("unknown section"))
        assertTrue(error(default.substringBefore("\n[symbols]")).reason.contains("no [symbols]"))
        assertNull(Keymaps.parse(default.replace("[keys]", "[keys]\n# nothing new")).letters.keys.single { it.face.label == "w" }.longPress)
    }
}

/** The layers as they were defined in Kotlin before keymaps were text. */
private object Golden {
    /**
     * Diagonal extras on the split punctuation keys, in every layer. Only
     * diagonals: the top and bottom faces already own up and down.
     */
    private val punctuation = mapOf(
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
    )

    /**
     * Typewise's honeycomb, read off a screenshot of its German layout with
     * `y` and `z` swapped back to QWERTY places. The two space keys flank
     * `f h` in the middle row; shift and delete hang off the screen edges
     * beside them. Dragging the left space moves the cursor and dragging
     * enter selects; the right space scrubs and recalls like the letters.
     * A long press on `a o u s` gives the German umlauts and `ß`.
     */
    val english = Layout.parse(
        listOf(
            "· w e t y i o",
            "q a r g u l p",
            "⇧ ,/.:fixed ␣:move f h ␣ !/?:fixed ⌫",
            "z s d n m j k",
            "· x c v b 😊/123 ⏎:select",
        ),
        bare = setOf("⇧", "⌫"),
        alternates = punctuation,
        longPress = mapOf("a" to "ä", "o" to "ö", "u" to "ü", "s" to "ß"),
    )

    /**
     * Typewise's symbols layer, read off a screenshot of the app: digits
     * running 1 to 0 down the middle, paired symbols on split keys, on the
     * same honeycomb as the letters so the spaces, delete and enter stay put.
     * A tap on the bottom function key goes back to letters.
     */
    val symbols = Layout.parse(
        listOf(
            "· ~/^ 1 2 3 4 >/<:fixed",
            "$/€:fixed =/+ 5 6 7 [/(:fixed ]/):fixed",
            "⇧ ,/.:fixed ␣:move 8 9 ␣ !/?:fixed ⌫",
            "°/§ –/- _ 0 `/* |//:fixed ¡/¿",
            "· & % @ # 😊/ABC ⏎:select",
        ),
        bare = setOf("⇧", "⌫"),
        alternates = punctuation + mapOf(
            ">/<" to mapOf(Direction.UP_RIGHT to "»", Direction.DOWN_RIGHT to "«"),
            "$/€" to mapOf(Direction.DOWN_RIGHT to "£"),
            "[/(" to mapOf(Direction.DOWN_RIGHT to "{"),
            "]/)" to mapOf(Direction.DOWN_RIGHT to "}"),
            "|//" to mapOf(Direction.DOWN_RIGHT to "\\"),
        ),
    )
}
