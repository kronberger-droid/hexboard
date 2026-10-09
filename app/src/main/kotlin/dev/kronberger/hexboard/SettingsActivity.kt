package dev.kronberger.hexboard

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.kronberger.hexboard.core.Choice
import dev.kronberger.hexboard.core.Setting
import dev.kronberger.hexboard.core.Settings
import dev.kronberger.hexboard.core.Slider
import dev.kronberger.hexboard.core.Toggle

/**
 * The settings screen, built in code from [Settings.all]. Changes are saved
 * at once and the keyboard picks them up the next time it opens.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        val pad = (16 * dp).toInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        list.addView(button("Enable Hexboard") { startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS)) })
        list.addView(button("Switch keyboard") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
        list.addView(button("Edit keymap") { startActivity(Intent(this, KeymapActivity::class.java)) })
        for (s in Settings.all) list.addView(row(s))
        val scroll = ScrollView(this).apply { addView(list) }
        keepClearOfBars(scroll)
        setContentView(scroll)
    }

    private fun row(s: Setting): View = when (s) {
        is Toggle -> Switch(this).apply {
            text = s.title
            isChecked = prefs[s]
            setOnCheckedChangeListener { _, on -> prefs[s] = on }
            spaced()
        }
        is Slider -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val label = TextView(context)
            fun show(v: Int) {
                label.text = "${s.title}: $v${s.unit}"
            }
            show(prefs[s])
            addView(label)
            addView(
                SeekBar(context).apply {
                    max = s.max - s.min
                    progress = prefs[s] - s.min
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            show(s.min + progress)
                            if (fromUser) prefs[s] = s.min + progress
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    })
                },
            )
            spaced()
        }
        is Choice -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = s.title })
            val group = RadioGroup(context)
            val ids = s.options.map { option ->
                RadioButton(context).apply {
                    id = View.generateViewId()
                    text = option
                    group.addView(this)
                }.id
            }
            group.check(ids[prefs[s]])
            group.setOnCheckedChangeListener { _, checked -> prefs[s] = ids.indexOf(checked) }
            addView(group)
            spaced()
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun View.spaced() {
        setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
    }
}

/**
 * Pad [view] clear of the status and navigation bars and the keyboard,
 * which an app targeting API 35 draws under otherwise.
 */
fun keepClearOfBars(view: View) {
    view.setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            val i = insets.getInsets(types)
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(l, t, r, b)
        insets
    }
}
