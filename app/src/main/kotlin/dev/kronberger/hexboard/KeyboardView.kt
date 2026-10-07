package dev.kronberger.hexboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import dev.kronberger.hexboard.core.keyboardHeightPx

/** Phase 1 placeholder: one rectangle that commits "a" on tap. */
class KeyboardView(
    context: Context,
    private val commit: (String) -> Unit,
) : View(context) {

    private val fill = Paint().apply { color = Color.rgb(0x30, 0x34, 0x3c) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 32f * resources.displayMetrics.density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = resources.displayMetrics
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            keyboardHeightPx(metrics.heightPixels, metrics.density),
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val baseline = height / 2f - (label.ascent() + label.descent()) / 2f
        canvas.drawText("a", width / 2f, baseline, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            commit("a")
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
