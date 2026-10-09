package dev.kronberger.hexboard.core

/** One user setting: its storage key, what the settings screen calls it, and its default. */
sealed interface Setting {
    val key: String
    val title: String
}

data class Toggle(override val key: String, override val title: String, val default: Boolean) : Setting

/** A whole number from [min] to [max], shown followed by [unit]. */
data class Slider(
    override val key: String,
    override val title: String,
    val min: Int,
    val max: Int,
    val default: Int,
    val unit: String,
) : Setting {
    init {
        require(default in min..max) { "$key: default $default outside $min..$max" }
    }
}

/** An opaque color, stored as ARGB. */
data class ColorSetting(override val key: String, override val title: String, val default: Int) : Setting

/** One of [options], stored as its index. */
data class Choice(override val key: String, override val title: String, val options: List<String>, val default: Int) : Setting

/** Every setting, in the order the settings screen shows them. */
object Settings {
    val autoCaps = Toggle("auto_caps", "Capitalize sentences", true)
    val doubleSpace = Toggle("double_space", "Double space types a period", true)
    val haptics = Toggle("haptics", "Vibrate on key press", true)
    val theme = Choice("theme", "Theme", listOf("Follow system", "Light", "Dark", "Custom"), 0)

    /** The custom theme's colors; the rest of its shades are mixed from these. Dark by default. */
    val background = ColorSetting("color_background", "Custom: background", 0xff121212.toInt())
    val keyColor = ColorSetting("color_key", "Custom: keys", 0xff2e2e2e.toInt())
    val labelColor = ColorSetting("color_label", "Custom: labels", 0xffffffff.toInt())
    val spaceColor = ColorSetting("color_space", "Custom: space keys", 0xff484848.toInt())
    val enterColor = ColorSetting("color_enter", "Custom: enter", 0xff5a5a5a.toInt())
    val size = Slider("size_percent", "Keyboard size", 70, 100, 100, "%")
    val longPress = Slider("long_press_ms", "Long press delay", 200, 800, LONG_PRESS_MS.toInt(), " ms")
    val holdUp = Slider("hold_up_ms", "Swipe up and hold for a capital", 100, 600, HOLD_UP_MS.toInt(), " ms")
    val swipe = Slider("swipe_dp", "Swipe distance", 10, 30, SWIPE_THRESHOLD_DP.toInt(), " dp")
    val dragStep = Slider("drag_step_dp", "Slow drag, distance per character", 4, 16, DRAG_STEP_DP.toInt(), " dp")
    val dragGain = Slider("drag_gain", "Fast drag acceleration", 1, 16, DRAG_GAIN_MAX.toInt(), "×")
    val edgeRate = Slider("edge_rate", "Edge drag top speed", 30, 400, DRAG_EDGE_RATE_MAX.toInt(), " per second")

    val all: List<Setting> = listOf(
        autoCaps, doubleSpace, haptics, theme, background, keyColor, labelColor, spaceColor, enterColor, size, longPress, holdUp, swipe, dragStep, dragGain, edgeRate)
}
