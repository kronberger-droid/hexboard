package dev.kronberger.hexboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test
    fun everySettingHasItsOwnKey() {
        val keys = Settings.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun choicesDefaultToAnOption() {
        Settings.all.filterIsInstance<Choice>().forEach { assertEquals(true, it.default in it.options.indices) }
    }
}
