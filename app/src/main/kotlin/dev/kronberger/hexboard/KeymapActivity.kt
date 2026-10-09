package dev.kronberger.hexboard

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.kronberger.hexboard.core.KeymapError
import dev.kronberger.hexboard.core.Keymaps
import dev.kronberger.hexboard.core.Presets

/**
 * Edit the keymap as text. A preset fills the editor; Save checks the text
 * and stores it only if it parses, so the keyboard never gets a broken one.
 * The keyboard picks it up the next time it opens.
 */
class KeymapActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var editor: EditText
    private lateinit var problem: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        val look = SettingsLook(this)
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (preset in Presets.all) {
            presets.addView(
                Button(this).apply {
                    text = preset.name
                    isAllCaps = false
                    setOnClickListener { editor.setText(preset.text) }
                },
            )
        }

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            // Taken as typed: no suggestions, and no keyboard periods or capitals.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            setText(savedInstanceState?.getString(DRAFT) ?: prefs.keymap ?: Presets.DEFAULT.text)
        }

        problem = TextView(this).apply {
            setTextColor(Color.rgb(0xd3, 0x2f, 0x2f))
            setPadding(0, pad / 2, 0, pad / 2)
        }

        val save = Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            addView(look.header("Keymap") { finish() })
            addView(HorizontalScrollView(context).apply { addView(presets) })
            addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(problem)
            addView(save)
        }
        look.applyToWindow(column)
        keepClearOfBars(column)
        setContentView(column)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(DRAFT, editor.text.toString())
    }

    private fun save() {
        val text = editor.text.toString()
        val keymap = try {
            Keymaps.parse(text)
        } catch (e: KeymapError) {
            problem.text = "Line ${e.line}: ${e.reason}"
            showLine(text, e.line)
            return
        }
        problem.text = ""
        // The default preset is stored as no keymap, so it follows updates.
        prefs.keymap = text.takeUnless { Keymaps.isDefault(keymap) }
        Toast.makeText(this, "Saved. The keyboard uses it the next time it opens.", Toast.LENGTH_SHORT).show()
    }

    /** Put the cursor at the start of [line] of [text], counted from 1. */
    private fun showLine(text: String, line: Int) {
        val offset = text.lineSequence().take(line - 1).sumOf { it.length + 1 }.coerceAtMost(text.length)
        editor.requestFocus()
        editor.setSelection(offset)
    }

    private companion object {
        const val DRAFT = "draft"
    }
}
