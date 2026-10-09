package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorsTest {

    @Test
    fun hexColorsParseWithOrWithoutTheHash() {
        assertEquals(0xff12ab9f.toInt(), parseColor("#12AB9F"))
        assertEquals(0xff12ab9f.toInt(), parseColor(" 12ab9f "))
        assertNull(parseColor("#12ab9"))
        assertNull(parseColor("#12ab9g"))
        assertNull(parseColor("red"))
    }

    @Test
    fun colorsFormatBackToHex() {
        assertEquals("#00A0FF", formatColor(0xff00a0ff.toInt()))
        assertEquals("#000000", formatColor(0xff000000.toInt()))
    }

    @Test
    fun blendGoesChannelByChannel() {
        val black = 0xff000000.toInt()
        val white = 0xffffffff.toInt()
        assertEquals(black, blend(black, white, 0f))
        assertEquals(white, blend(black, white, 1f))
        assertEquals(0xff808080.toInt(), blend(black, white, 0.5f))
        assertEquals(0xff400020.toInt(), blend(0xff800000.toInt(), 0xff000040.toInt(), 0.5f))
    }

    @Test
    fun lightnessTellsLightFromDark() {
        assertEquals(0f, lightness(0xff000000.toInt()), 1e-6f)
        assertEquals(1f, lightness(0xffffffff.toInt()), 1e-6f)
        assertTrue(lightness(0xffe4e6ea.toInt()) > 0.5f)
        assertTrue(lightness(0xff2e2e2e.toInt()) < 0.5f)
    }
}
