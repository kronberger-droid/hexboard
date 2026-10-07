package dev.kronberger.hexboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import dev.kronberger.hexboard.core.HexGrid
import dev.kronberger.hexboard.core.Key
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Layout
import dev.kronberger.hexboard.core.keyboardHeightPx

/** Draws [layout] as a honeycomb and reports tapped keys to [onKey]. */
class KeyboardView(
    context: Context,
    private val layout: Layout,
    private val onKey: (Key) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val padding = 4f * density

    private val letterFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3a, 0x3f, 0x48) }
    private val functionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x26, 0x2a, 0x31) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private var grid: HexGrid? = null
    private var paths: Map<Key, Path> = emptyMap()
    private var downKey: Key? = null

    init {
        setBackgroundColor(Color.rgb(0x16, 0x18, 0x1c))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            keyboardHeightPx(layout.rows, resources.displayMetrics.heightPixels, density),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val g = HexGrid.fit(layout.cells, padding, padding, w - 2 * padding, h - 2 * padding)
        grid = g
        // Shrinking each hex a little leaves a gap between neighbours.
        paths = layout.keys.associateWith { key ->
            Path().apply {
                g.corners(key.pos, scale = 0.93f).forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
        }
        labelPaint.textSize = minOf(g.radiusX, g.radiusY) * 0.9f
    }

    override fun onDraw(canvas: Canvas) {
        val g = grid ?: return
        for ((key, path) in paths) {
            canvas.drawPath(path, if (key.action is KeyAction.Text) letterFill else functionFill)
            val c = g.center(key.pos)
            val baseline = c.y - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(key.label, c.x, baseline, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = grid ?: return false
        when (event.actionMasked) {
            // The key under the finger at touch down is the one that types,
            // so drifting while lifting does not change it.
            MotionEvent.ACTION_DOWN ->
                downKey = g.nearest(layout.cells, event.x, event.y)?.let { layout[it] }
            MotionEvent.ACTION_UP -> {
                downKey?.let(onKey)
                downKey = null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> downKey = null
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
