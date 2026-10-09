package dev.kronberger.hexboard

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView
import dev.kronberger.hexboard.core.blend

/**
 * The look of the settings screens, built in code: a soft background,
 * rounded cards of rows, and the system's accent color where there is one.
 * Follows the system's dark mode.
 */
class SettingsLook(private val activity: Activity) {
    private val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

    val background = if (night) 0xff111214.toInt() else 0xfff3f4f6.toInt()
    val card = if (night) 0xff1d1e22.toInt() else 0xffffffff.toInt()
    val text = if (night) 0xffeceef2.toInt() else 0xff1b1c1f.toInt()
    val muted = if (night) 0xff9aa0a8.toInt() else 0xff5f6670.toInt()
    val accent = if (Build.VERSION.SDK_INT >= 31) {
        activity.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
    } else {
        if (night) 0xff8ab4f8.toInt() else 0xff2f6fe4.toInt()
    }
    private val divider = blend(card, text, 0.08f)
    private val ripple = blend(card, text, 0.12f)

    fun dp(v: Float) = (v * activity.resources.displayMetrics.density).toInt()

    /** Paint the window's background and pick system bar icons that show on it. */
    fun applyToWindow(root: View) {
        root.setBackgroundColor(background)
        activity.window.setBackgroundDrawable(ColorDrawable(background))
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // Through the decor view, which this creates: before setContentView
            // the window's own insetsController throws.
            activity.window.decorView.windowInsetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
        }
    }

    fun label(text: String, sizeSp: Float, color: Int = this.text, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** A page's title, with a back arrow on [back]. */
    fun header(title: String, back: (() -> Unit)?): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), dp(24f), dp(8f), dp(16f))
        back?.let { onBack ->
            addView(
                label("←", 24f).apply {
                    setPadding(0, 0, dp(16f), dp(8f))
                    setOnClickListener { onBack() }
                    contentDescription = "Back"
                },
            )
        }
        addView(label(title, 32f, bold = true))
    }

    /** Rows in a rounded card, under an optional small heading. */
    fun group(heading: String?, rows: List<View>): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(20f))
        heading?.let {
            addView(label(it.uppercase(), 12f, muted, bold = true).apply { setPadding(dp(16f), 0, 0, dp(8f)); letterSpacing = 0.08f })
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(this@SettingsLook.card)
                cornerRadius = dp(20f).toFloat()
            }
            clipToOutline = true
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) {
                card.addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16f) })
            }
            card.addView(row)
        }
        addView(card)
    }

    /** A row: a title, an optional summary below it, and an optional [end] view on the right. */
    fun row(title: String, summary: String? = null, end: View? = null, onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f))
                summary?.let { addView(label(it, 13f, muted).apply { setPadding(0, dp(2f), 0, 0) }) }
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            end?.let { addView(it) }
            onClick?.let { click ->
                background = RippleDrawable(ColorStateList.valueOf(ripple), null, ColorDrawable(0xffffffff.toInt()))
                setOnClickListener { click() }
            }
        }

    /** The `›` at the end of a row that opens another page. */
    fun chevron() = label("›", 24f, muted)

    /** A round swatch of [color]. */
    fun swatch(color: Int) = View(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1f), blend(color, text, 0.3f))
        }
        layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f))
    }

    /** A card in the accent color, asking the user to do one thing. */
    fun callout(title: String, summary: String, action: String, onClick: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
        background = GradientDrawable().apply {
            setColor(blend(card, accent, 0.18f))
            cornerRadius = dp(20f).toFloat()
        }
        addView(label(title, 18f, bold = true))
        addView(label(summary, 14f, muted).apply { setPadding(0, dp(4f), 0, dp(12f)) })
        addView(
            label(action, 15f, if (night) 0xff111214.toInt() else 0xffffffff.toInt(), bold = true).apply {
                setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(20f).toFloat()
                }
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(20f) }
    }
}

/**
 * Pad [view] clear of the status and navigation bars and the keyboard,
 * which an app targeting API 35 draws under otherwise.
 */
fun keepClearOfBars(view: View) {
    // On top of the view's own padding, which the insets would replace.
    val own = listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    view.setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            val i = insets.getInsets(types)
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(own[0] + l, own[1] + t, own[2] + r, own[3] + b)
        insets
    }
}
