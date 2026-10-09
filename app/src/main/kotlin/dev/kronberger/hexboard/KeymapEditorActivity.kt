package dev.kronberger.hexboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import dev.kronberger.hexboard.core.Direction
import dev.kronberger.hexboard.core.Key
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Keyboard
import dev.kronberger.hexboard.core.Keymap
import dev.kronberger.hexboard.core.KeymapError
import dev.kronberger.hexboard.core.Keymaps
import dev.kronberger.hexboard.core.Layouts
import dev.kronberger.hexboard.core.Presets
import dev.kronberger.hexboard.core.blend

/**
 * The keymap, edited on the keyboard itself: tap a key to give it a long
 * press and swipes, long-press one and drag it onto another to swap them.
 * Every change is saved at once, as keymap text, which the text editor
 * opens for everything else.
 */
class KeymapEditorActivity : Activity(), KeyboardView.Edits {

    private lateinit var prefs: Prefs
    private lateinit var look: SettingsLook
    private lateinit var keymap: Keymap
    private val keyboard = Keyboard(Layouts.english, Layouts.symbols)
    private lateinit var keys: KeyboardView
    private lateinit var layers: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        look = SettingsLook(this)
        keys = KeyboardView(this, keyboard) {}.also { it.edits = this }

        layers = listOf("Letters", "Symbols").mapIndexed { i, name ->
            look.label(name, 15f, bold = true).apply {
                setPadding(look.dp(18f), look.dp(8f), look.dp(18f), look.dp(8f))
                setOnClickListener { showLayer(i == 1) }
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(16f), 0, look.dp(16f), look.dp(16f))
            addView(look.header("Keymap") { finish() })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layers.forEach { addView(it) }
                },
            )
            addView(
                look.label(
                    "Tap a key to give it a long press and swipes. Long-press a key and drag it onto another to swap them.",
                    14f,
                    look.muted,
                ).apply { setPadding(0, look.dp(16f), 0, look.dp(16f)) },
            )
            addView(
                look.group(
                    null,
                    listOf(
                        look.row("Edit as text", "Presets, and changing what a key is", look.chevron()) {
                            startActivity(Intent(this@KeymapEditorActivity, KeymapActivity::class.java))
                        },
                    ),
                ),
            )
        }
        // The status bar over the top part; the keyboard keeps clear of the
        // navigation bar by itself, as it does in its own window.
        top.setOnApplyWindowInsetsListener { v, insets ->
            val t = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            v.setPadding(v.paddingLeft, t, v.paddingRight, v.paddingBottom)
            insets
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(keys)
        }
        look.applyToWindow(root)
        setContentView(root)
    }

    // Again on every return, since the text editor may have changed the keymap.
    override fun onResume() {
        super.onResume()
        keymap = prefs.keymap?.let { runCatching { Keymaps.parse(it) }.getOrNull() } ?: Keymaps.parse(Presets.DEFAULT.text)
        keys.configure(prefs)
        keys.applyPalette(paletteFor(prefs, resources))
        showKeymap()
        showLayer(keyboard.showingSymbols)
    }

    private fun showKeymap() {
        keyboard.setLayouts(keymap.letters, keymap.symbols)
        keys.invalidate()
    }

    private fun showLayer(symbols: Boolean) {
        keyboard.showingSymbols = symbols
        layers.forEachIndexed { i, v ->
            val on = (i == 1) == symbols
            v.background = if (on) {
                GradientDrawable().apply {
                    setColor(blend(look.card, look.accent, 0.25f))
                    cornerRadius = look.dp(18f).toFloat()
                }
            } else {
                null
            }
        }
        keys.invalidate()
    }

    override fun swapped(from: Key, to: Key) {
        save(keymap.swapped(keyboard.showingSymbols, from.pos, to.pos))
    }

    override fun tapped(key: Key) {
        val token = Keymaps.token(key)
        val split = key.lower != null
        val repeats = key.face.action == KeyAction.Delete

        fun field(hint: String, value: String?, enabled: Boolean = true) = EditText(this).apply {
            this.hint = hint
            setText(value.orEmpty())
            isSingleLine = true
            isEnabled = enabled
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val hold = field(if (repeats) "repeats" else "long press", key.longPress, enabled = !repeats)
        // The six swipes laid out the way they go.
        val swipes = listOf(
            Direction.UP_LEFT to "↖",
            Direction.UP to if (split) "top half" else "↑ capital",
            Direction.UP_RIGHT to if (key.longPress != null) "↗ = long press" else "↗",
            Direction.DOWN_LEFT to "↙",
            Direction.DOWN to if (split) "bottom half" else "↓ lowercase",
            Direction.DOWN_RIGHT to "↘",
        ).associate { (direction, hint) ->
            val upDown = direction == Direction.UP || direction == Direction.DOWN
            direction to field(hint, key.alternates[direction], enabled = !(split && upDown))
        }
        val grid = GridLayout(this).apply {
            columnCount = 3
            swipes.values.forEach { addView(it, GridLayout.LayoutParams().apply { width = look.dp(88f) }) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(24f), look.dp(8f), look.dp(24f), 0)
            addView(look.label("Long press", 13f, look.muted))
            addView(hold)
            addView(look.label("Swipes", 13f, look.muted).apply { setPadding(0, look.dp(12f), 0, 0) })
            addView(grid)
        }
        AlertDialog.Builder(this)
            .setTitle(token.substringBefore(':'))
            .setView(body)
            .setPositiveButton("Save") { _, _ ->
                val texts = (listOf(hold) + swipes.values).map { it.text.toString().trim() }
                if (texts.any { it.any(Char::isWhitespace) }) {
                    Toast.makeText(this, "A long press or swipe types text without spaces.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val alternates = swipes.mapNotNull { (d, f) -> f.text.toString().trim().takeIf { it.isNotEmpty() }?.let { d to it } }.toMap()
                save(keymap.withAddOns(token, hold.text.toString().trim().ifEmpty { null }, alternates))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Store [next] as text once it reads back, and show it. */
    private fun save(next: Keymap) {
        val text = Keymaps.write(next)
        try {
            Keymaps.parse(text)
        } catch (e: KeymapError) {
            Toast.makeText(this, "Not saved: ${e.reason}", Toast.LENGTH_LONG).show()
            return
        }
        keymap = next
        // The default keymap is stored as none, so it follows updates.
        val default = Keymaps.parse(Presets.DEFAULT.text)
        val isDefault = default.letters.keys == next.letters.keys && default.symbols.keys == next.symbols.keys
        prefs.keymap = if (isDefault) null else text
        showKeymap()
    }
}
