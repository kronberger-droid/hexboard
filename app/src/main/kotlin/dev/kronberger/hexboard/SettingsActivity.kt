package dev.kronberger.hexboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import dev.kronberger.hexboard.core.Choice
import dev.kronberger.hexboard.core.ColorSetting
import dev.kronberger.hexboard.core.Setting
import dev.kronberger.hexboard.core.Settings
import dev.kronberger.hexboard.core.Slider
import dev.kronberger.hexboard.core.Toggle
import dev.kronberger.hexboard.core.formatColor
import dev.kronberger.hexboard.core.parseColor

/**
 * The settings app. The main page holds what matters most, setting the
 * keyboard up, its theme and its keymap; the rest sits on sub-pages,
 * each the same activity started with [PAGE]. Changes are saved at once
 * and the keyboard picks them up the next time it opens.
 */
class SettingsActivity : Activity() {

    private enum class Page(val title: String, val summary: String, val settings: List<Setting>) {
        TYPING("Typing", "Capitals, periods, vibration, long press", listOf(Settings.autoCaps, Settings.doubleSpace, Settings.haptics, Settings.longPress)),
        APPEARANCE("Appearance", "Keyboard size and custom colors", listOf(Settings.size)),
        GESTURES("Gestures", "Swipe distance and how drags feel", listOf(Settings.swipe, Settings.dragStep, Settings.dragGain, Settings.edgeRate)),
    }

    private lateinit var prefs: Prefs
    private lateinit var look: SettingsLook
    private var page: Page? = null

    /** Whether Hexboard was enabled and selected when the page was last built. */
    private var setupState: Pair<Boolean, Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        look = SettingsLook(this)
        page = intent.getStringExtra(PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() }
    }

    // Rebuilt on every return, so the main page shows what a sub-page or
    // the system's keyboard settings changed.
    override fun onResume() {
        super.onResume()
        build()
    }

    // The keyboard picker is a dialog over this activity, which does not pause it.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && page == null && setupState != setup()) build()
    }

    private fun build() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(16f), 0, look.dp(16f), look.dp(24f))
        }
        when (val p = page) {
            null -> main(column)
            else -> sub(column, p)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        look.applyToWindow(scroll)
        keepClearOfBars(scroll)
        setContentView(scroll)
    }

    private fun main(column: LinearLayout) {
        column.addView(look.header("Hexboard", null))
        val (enabled, selected) = setup().also { setupState = it }
        when {
            !enabled -> column.addView(
                look.callout("Turn on Hexboard", "Allow it in the system's keyboard list first.", "Open keyboard settings") {
                    startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
                },
            )
            !selected -> column.addView(
                look.callout("Make it your keyboard", "Hexboard is on, but another keyboard is in use.", "Switch keyboard") {
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                },
            )
        }
        column.addView(
            look.group(
                null,
                listOf(
                    choiceRow(Settings.theme),
                    look.row("Keymap", "Layout, long presses and swipes", look.chevron()) {
                        startActivity(Intent(this, KeymapEditorActivity::class.java))
                    },
                ),
            ),
        )
        column.addView(
            look.group(
                "More",
                Page.entries.map { p ->
                    look.row(p.title, p.summary, look.chevron()) {
                        startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, p.name))
                    }
                },
            ),
        )
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        column.addView(
            look.group(
                null,
                listOf(
                    look.row("About", "Hexboard $version · source on GitHub") {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kronberger-droid/hexboard")))
                    },
                ),
            ),
        )
    }

    private fun sub(column: LinearLayout, p: Page) {
        column.addView(look.header(p.title) { finish() })
        column.addView(look.group(null, p.settings.map(::settingRow)))
        if (p == Page.APPEARANCE) {
            val colors = listOf(Settings.background, Settings.keyColor, Settings.labelColor, Settings.spaceColor, Settings.enterColor)
            column.addView(look.group("Custom theme colors", colors.map(::colorRow)))
            column.addView(
                look.label("Used when the theme is set to Custom.", 13f, look.muted).apply {
                    setPadding(look.dp(16f), 0, look.dp(16f), 0)
                },
            )
        }
    }

    /** Whether Hexboard is enabled in the system, and whether it is the keyboard in use. */
    private fun setup(): Pair<Boolean, Boolean> {
        val me = ComponentName(this, HexboardService::class.java)
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.component == me }
        val current = SystemSettings.Secure.getString(contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD)
        return enabled to (current?.let(ComponentName::unflattenFromString) == me)
    }

    private fun settingRow(s: Setting): View = when (s) {
        is Toggle -> toggleRow(s)
        is Slider -> sliderRow(s)
        is Choice -> choiceRow(s)
        is ColorSetting -> colorRow(s)
    }

    private fun toggleRow(t: Toggle): View {
        val switch = Switch(this).apply {
            isChecked = prefs[t]
            isClickable = false
            isFocusable = false
        }
        return look.row(t.title, end = switch) {
            switch.isChecked = !switch.isChecked
            prefs[t] = switch.isChecked
        }
    }

    private fun choiceRow(c: Choice): View = look.row(c.title, c.options[prefs[c]], look.chevron()) {
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setSingleChoiceItems(c.options.toTypedArray(), prefs[c]) { dialog, i ->
                prefs[c] = i
                dialog.dismiss()
                build()
            }
            .show()
    }

    private fun sliderRow(s: Slider): View {
        val value = look.label("${prefs[s]}${s.unit}", 14f, look.muted)
        val row = look.row(s.title, end = value)
        val bar = SeekBar(this).apply {
            max = s.max - s.min
            progress = prefs[s] - s.min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = "${s.min + progress}${s.unit}"
                    if (fromUser) prefs[s] = s.min + progress
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(
                bar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = look.dp(12f)
                },
            )
        }
    }

    private fun colorRow(c: ColorSetting): View {
        val title = c.title.removePrefix("Custom: ").replaceFirstChar { it.uppercase() }
        return look.row(title, formatColor(prefs[c]), look.swatch(prefs[c])) {
            val preview = look.swatch(prefs[c])
            val field = EditText(this).apply {
                setText(formatColor(prefs[c]))
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(text: Editable) {
                        val color = parseColor(text.toString())
                        error = if (color == null) "#RRGGBB" else null
                        if (color != null) preview.background = look.swatch(color).background
                    }

                    override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) = Unit
                })
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(look.dp(24f), look.dp(8f), look.dp(24f), 0)
                addView(preview)
                addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.dp(16f) })
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(body)
                .setPositiveButton("Save") { _, _ ->
                    parseColor(field.text.toString())?.let {
                        prefs[c] = it
                        build()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private companion object {
        const val PAGE = "page"
    }
}
