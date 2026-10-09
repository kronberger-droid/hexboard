package dev.kronberger.hexboard

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets

/** Height of the system's IME button strip where the platform does not say (AOSP's value). */
private const val IME_NAV_BAR_DP = 48f

/**
 * The height of the strip the system draws the IME's hide and switcher
 * buttons in: the framework's own `navigation_bar_frame_height`, which an
 * OEM may change, or the AOSP default.
 */
@SuppressLint("DiscouragedApi", "InternalInsetResource")
private fun imeStripPx(resources: Resources): Int {
    val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
    val px = if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    return if (px > 0) px else (IME_NAV_BAR_DP * resources.displayMetrics.density).toInt()
}

/**
 * How far keyboard content must stay from the left, right and bottom edges
 * of the IME window; `top` is always 0.
 *
 * With gesture navigation the IME only gets the 24dp gesture inset, yet the
 * system draws its hide and switcher buttons centered in a taller strip at
 * the bottom of the window. The whole strip is kept clear. The inset is read
 * ignoring visibility: in an app that hides the navigation bar it reports 0,
 * while the system still draws the IME's buttons.
 */
fun keyboardInsets(insets: WindowInsets, resources: Resources): Rect {
    val (l, r, nav) = if (Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
        Triple(i.left, i.right, i.bottom)
    } else {
        @Suppress("DEPRECATION")
        Triple(insets.systemWindowInsetLeft, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }
    val b = if (nav > 0) maxOf(nav, imeStripPx(resources)) else 0
    return Rect(l, 0, r, b)
}
