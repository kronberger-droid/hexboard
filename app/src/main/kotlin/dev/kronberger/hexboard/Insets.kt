package dev.kronberger.hexboard

import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets

/** Height of the system's IME button strip (AOSP navigation_bar_frame_height). */
private const val IME_NAV_BAR_DP = 48f

/**
 * How far keyboard content must stay from the left, right and bottom edges
 * of the IME window; `top` is always 0.
 *
 * With gesture navigation the IME only gets the 24dp gesture inset, yet the
 * system draws its hide and switcher buttons centered in a 48dp strip at the
 * bottom of the window. The whole strip is kept clear.
 */
fun keyboardInsets(insets: WindowInsets, density: Float): Rect {
    val (l, r, nav) = if (Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
        Triple(i.left, i.right, i.bottom)
    } else {
        @Suppress("DEPRECATION")
        Triple(insets.systemWindowInsetLeft, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }
    val b = if (nav > 0) maxOf(nav, (IME_NAV_BAR_DP * density).toInt()) else 0
    return Rect(l, 0, r, b)
}
