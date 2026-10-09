package dev.kronberger.hexboard

import android.content.res.Configuration
import android.content.res.Resources
import dev.kronberger.hexboard.core.Settings
import dev.kronberger.hexboard.core.blend
import dev.kronberger.hexboard.core.lightness

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

        /**
         * A palette from five chosen colors. The split keys' lower half, the
         * pressed key and the hints are mixed from them, and the system bars
         * get dark icons on a light [background].
         */
        fun custom(background: Int, key: Int, label: Int, space: Int, enter: Int) = Palette(
            background = background,
            key = key,
            lower = blend(key, background, 0.25f),
            space = space,
            enter = enter,
            pressed = blend(key, label, 0.35f),
            label = label,
            hint = blend(label, key, 0.4f),
            light = lightness(background) > 0.5f,
        )

        /** The palette matching the system's dark mode. */
        fun of(resources: Resources): Palette {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
        }
    }
}

/** The palette the user's theme setting asks for. */
fun paletteFor(prefs: Prefs, resources: Resources): Palette = when (prefs[Settings.theme]) {
    1 -> Palette.LIGHT
    2 -> Palette.DARK
    3 -> Palette.custom(
        prefs[Settings.background],
        prefs[Settings.keyColor],
        prefs[Settings.labelColor],
        prefs[Settings.spaceColor],
        prefs[Settings.enterColor],
    )
    else -> Palette.of(resources)
}
