package dev.kronberger.hexboard

import android.content.res.Configuration
import android.content.res.Resources

/** The keyboard's colors, as opaque ARGB. */
data class Palette(
    val background: Int,
    val key: Int,
    /** The lower half of a split key. */
    val lower: Int,
    val space: Int,
    val enter: Int,
    val pressed: Int,
    val label: Int,
    /** Swipe hints, emoji headers and the emoji tab underline. */
    val hint: Int,
    /** Whether system bars drawn over it need dark icons. */
    val light: Boolean,
) {
    companion object {
        val DARK = Palette(
            background = 0xff121212.toInt(),
            key = 0xff2e2e2e.toInt(),
            lower = 0xff262626.toInt(),
            space = 0xff484848.toInt(),
            enter = 0xff5a5a5a.toInt(),
            pressed = 0xff7a7a7a.toInt(),
            label = 0xffffffff.toInt(),
            hint = 0xff9a9a9a.toInt(),
            light = false,
        )

        val LIGHT = Palette(
            background = 0xffe4e6ea.toInt(),
            key = 0xffffffff.toInt(),
            lower = 0xfff1f2f4.toInt(),
            space = 0xffc9ccd2.toInt(),
            enter = 0xffb7bcc4.toInt(),
            pressed = 0xffa9aeb6.toInt(),
            label = 0xff1b1b1b.toInt(),
            hint = 0xff6b6f76.toInt(),
            light = true,
        )

        /** The palette matching the system's dark mode. */
        fun of(resources: Resources): Palette {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
        }
    }
}
