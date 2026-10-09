package dev.kronberger.hexboard.core

/** `#RRGGBB`, with or without the `#`, as an opaque ARGB int; null if it is not one. */
fun parseColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
    return (0xff000000 or hex.toLong(16)).toInt()
}

/** [argb] as `#RRGGBB`. */
fun formatColor(argb: Int): String = "#" + (argb and 0xffffff).toString(16).padStart(6, '0').uppercase()

/** [a] moved [t] of the way towards [b], channel by channel; opaque. */
fun blend(a: Int, b: Int, t: Float): Int {
    fun channel(shift: Int): Int {
        val x = (a shr shift) and 0xff
        val y = (b shr shift) and 0xff
        return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255) shl shift
    }
    return (0xff shl 24) or channel(16) or channel(8) or channel(0)
}

/** How light [argb] looks, from 0 for black to 1 for white. */
fun lightness(argb: Int): Float {
    val r = (argb shr 16) and 0xff
    val g = (argb shr 8) and 0xff
    val b = argb and 0xff
    return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
}
