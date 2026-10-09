package dev.kronberger.hexboard

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
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

    /** Recently picked emoji, as [dev.kronberger.hexboard.core.Recents] stores them. */
    var recentEmoji: String?
        get() = shared.getString("recent_emoji", null)
        set(value) = shared.edit().putString("recent_emoji", value).apply()

    /** The user's keymap text, or null for the default preset. */
    var keymap: String?
        get() = shared.getString("keymap", null)
        set(value) = shared.edit().putString("keymap", value).apply()

    companion object {
        private const val NAME = "hexboard"

        /** Whether preferences from before direct boot support were moved over yet. */
        @Volatile
        private var moved = false

        /**
         * The preferences, in device-protected storage, which is readable
         * before the first unlock after a reboot, so the keyboard can type
         * the PIN. Ones kept in credential storage by earlier versions move
         * over once the user has unlocked.
         */
        fun of(context: Context): Prefs {
            val device = context.createDeviceProtectedStorageContext()
            if (!moved && context.getSystemService(UserManager::class.java).isUserUnlocked) {
                // True when moved or when there was nothing to move.
                moved = device.moveSharedPreferencesFrom(context, NAME)
            }
            return Prefs(device.getSharedPreferences(NAME, Context.MODE_PRIVATE))
        }
    }
}
