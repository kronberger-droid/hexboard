package dev.kronberger.hexboard

import android.content.Context
import android.content.SharedPreferences
import dev.kronberger.hexboard.core.Choice
import dev.kronberger.hexboard.core.ColorSetting
import dev.kronberger.hexboard.core.Slider
import dev.kronberger.hexboard.core.Toggle

/** The [dev.kronberger.hexboard.core.Settings] values as stored, defaults for the unset. */
class Prefs(val shared: SharedPreferences) {
    operator fun get(t: Toggle) = shared.getBoolean(t.key, t.default)
    operator fun get(s: Slider) = shared.getInt(s.key, s.default).coerceIn(s.min, s.max)
    operator fun get(c: Choice) = shared.getInt(c.key, c.default).coerceIn(0, c.options.lastIndex)

    operator fun get(c: ColorSetting) = shared.getInt(c.key, c.default) or (0xff shl 24)

    operator fun set(c: ColorSetting, value: Int) = shared.edit().putInt(c.key, value).apply()
    operator fun set(t: Toggle, value: Boolean) = shared.edit().putBoolean(t.key, value).apply()
    operator fun set(s: Slider, value: Int) = shared.edit().putInt(s.key, value).apply()
    operator fun set(c: Choice, value: Int) = shared.edit().putInt(c.key, value).apply()

    /** The user's keymap text, or null for the default preset. */
    var keymap: String?
        get() = shared.getString("keymap", null)
        set(value) = shared.edit().putString("keymap", value).apply()

    companion object {
        fun of(context: Context) = Prefs(context.getSharedPreferences("hexboard", Context.MODE_PRIVATE))
    }
}
