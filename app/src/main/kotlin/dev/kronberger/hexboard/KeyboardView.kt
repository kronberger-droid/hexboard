package dev.kronberger.hexboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import dev.kronberger.hexboard.core.Face
import dev.kronberger.hexboard.core.HexGrid
import dev.kronberger.hexboard.core.Key
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Layout
import dev.kronberger.hexboard.core.keyboardHeightPx

/** Draws [layout] as a honeycomb and reports tapped faces to [onFace]. */
class KeyboardView(
    context: Context,
    private val layout: Layout,
    private val onFace: (Face) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val padding = 4f * density

    private val keyFill = paint(0x3a3f48)
    private val lowerFill = paint(0x30343c)
    private val spaceFill = paint(0x5c616b)
    private val enterFill = paint(0x3463e0)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    /** System bars the keyboard must stay clear of, e.g. the IME switcher row. */
    private var insetLeft = 0
    private var insetRight = 0
    private var insetBottom = 0

    private var grid: HexGrid? = null
    private var paths: Map<Key, Path> = emptyMap()
    private var downFace: Face? = null

    init {
        setBackgroundColor(Color.rgb(0x16, 0x18, 0x1c))
    }

    private fun paint(rgb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(rgb shr 16, (rgb shr 8) and 0xff, rgb and 0xff) }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val (l, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            Triple(i.left, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            Triple(insets.systemWindowInsetLeft, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        if (l != insetLeft || r != insetRight || b != insetBottom) {
            insetLeft = l
            insetRight = r
            insetBottom = b
            requestLayout()
        }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val keysWidth = (width - insetLeft - insetRight - 2 * padding).toInt()
        val keysHeight = keyboardHeightPx(layout, keysWidth, resources.displayMetrics.heightPixels)
        setMeasuredDimension(width, keysHeight + (2 * padding).toInt() + insetBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val g = HexGrid.fit(
            layout.fitCells,
            left = insetLeft + padding,
            top = padding,
            width = w - insetLeft - insetRight - 2 * padding,
            height = h - insetBottom - 2 * padding,
        )
        grid = g
        // Shrinking each hex a little leaves a gap between neighbours.
        paths = layout.keys.filterNot { it.bare }.associateWith { key ->
            Path().apply {
                g.corners(key.pos, scale = 0.94f).forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
        }
        labelPaint.textSize = g.radius * 0.6f
    }

    override fun onDraw(canvas: Canvas) {
        val g = grid ?: return
        for (key in layout.keys) {
            val c = g.center(key.pos)
            paths[key]?.let { path ->
                canvas.drawPath(path, fillFor(key.face.action))
                if (key.lower != null) {
                    canvas.save()
                    canvas.clipRect(0f, c.y, width.toFloat(), height.toFloat())
                    canvas.drawPath(path, lowerFill)
                    canvas.restore()
                }
            }
            // Bare keys sit on hexes half off-screen; pull their labels in.
            val x = c.x.coerceIn(insetLeft + labelPaint.textSize, width - insetRight - labelPaint.textSize)
            if (key.lower == null) {
                drawLabel(canvas, key.face, x, c.y)
            } else {
                drawLabel(canvas, key.face, x, c.y - g.radius * 0.45f)
                drawLabel(canvas, key.lower, x, c.y + g.radius * 0.45f)
            }
        }
    }

    private fun fillFor(action: KeyAction) = when (action) {
        KeyAction.Space -> spaceFill
        KeyAction.Enter -> enterFill
        else -> keyFill
    }

    private fun drawLabel(canvas: Canvas, face: Face, x: Float, y: Float) {
        if (face.action == KeyAction.Space) return
        val baseline = y - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(face.label, x, baseline, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = grid ?: return false
        when (event.actionMasked) {
            // The face under the finger at touch down is the one that types,
            // so drifting while lifting does not change it.
            MotionEvent.ACTION_DOWN -> downFace = layout.faceAt(g, event.x, event.y)
            MotionEvent.ACTION_UP -> {
                downFace?.let(onFace)
                downFace = null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> downFace = null
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
