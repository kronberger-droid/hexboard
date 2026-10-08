package dev.kronberger.hexboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import dev.kronberger.hexboard.core.Direction
import dev.kronberger.hexboard.core.Face
import dev.kronberger.hexboard.core.HexGrid
import dev.kronberger.hexboard.core.Key
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Keyboard
import dev.kronberger.hexboard.core.Layout
import dev.kronberger.hexboard.core.SWIPE_THRESHOLD_DP
import dev.kronberger.hexboard.core.ShiftState
import dev.kronberger.hexboard.core.Point
import dev.kronberger.hexboard.core.DRAG_EDGE_DP
import dev.kronberger.hexboard.core.DRAG_EDGE_RAMP_MS
import dev.kronberger.hexboard.core.DRAG_EDGE_RATE_MAX
import dev.kronberger.hexboard.core.DRAG_EDGE_RATE_START
import dev.kronberger.hexboard.core.DRAG_FAST_DP_S
import dev.kronberger.hexboard.core.DRAG_FLICK_DP_S
import dev.kronberger.hexboard.core.DRAG_FLICK_MS
import dev.kronberger.hexboard.core.DRAG_GAIN_MAX
import dev.kronberger.hexboard.core.DRAG_SLOW_DP_S
import dev.kronberger.hexboard.core.DRAG_SMOOTH_MS
import dev.kronberger.hexboard.core.DRAG_STEP_DP
import dev.kronberger.hexboard.core.DragEdge
import dev.kronberger.hexboard.core.DragGain
import dev.kronberger.hexboard.core.TouchEvent
import dev.kronberger.hexboard.core.Touches
import dev.kronberger.hexboard.core.keyboardHeightPx

/** Draws [keyboard]'s layout as a honeycomb and reports resolved actions to [onAction]. */
class KeyboardView(
    context: Context,
    private val keyboard: Keyboard,
    private val onAction: (KeyAction) -> Unit,
) : View(context) {

    private val layout get() = keyboard.layout
    private var builtFor: Layout? = null
    private val density = resources.displayMetrics.density
    private val padding = 4f * density
    private val swipeThreshold = SWIPE_THRESHOLD_DP * density

    private val keyFill = paint(0x2e2e2e)
    private val lowerFill = paint(0x262626)
    private val spaceFill = paint(0x484848)
    private val enterFill = paint(0x5a5a5a)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    /** Multi-character labels such as `123`, which would crowd a hex at full size. */
    private val smallLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x9a, 0x9a, 0x9a)
        textAlign = Paint.Align.CENTER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** System bars the keyboard must stay clear of, e.g. the IME switcher row. */
    private var insetLeft = 0
    private var insetRight = 0
    private var insetBottom = 0

    private var grid: HexGrid? = null
    private var paths: Map<Key, Path> = emptyMap()

    private val touches = Touches(
        swipeThreshold,
        DragGain(
            DRAG_STEP_DP * density,
            DRAG_SLOW_DP_S * density,
            DRAG_FAST_DP_S * density,
            DRAG_GAIN_MAX,
            DRAG_SMOOTH_MS,
        ),
        DRAG_FLICK_MS,
        DRAG_FLICK_DP_S * density,
        DragEdge(DRAG_EDGE_DP * density, DRAG_EDGE_RATE_START, DRAG_EDGE_RATE_MAX, DRAG_EDGE_RAMP_MS),
    )

    /** Advances drags held in an edge strip every frame while one runs. */
    private val ticker = object : Runnable {
        var scheduled = false

        override fun run() {
            scheduled = false
            touches.tick(SystemClock.uptimeMillis()).forEach(::handle)
            keepTicking()
        }
    }

    private fun keepTicking() {
        if (touches.dragging && !ticker.scheduled) {
            ticker.scheduled = true
            postOnAnimation(ticker)
        }
    }

    init {
        setBackgroundColor(Color.rgb(0x12, 0x12, 0x12))
    }

    private companion object {
        /** Unit vectors in screen coordinates for placing swipe hints. */
        val HINT_OFFSETS = mapOf(
            Direction.UP to (0f to -1f),
            Direction.UP_RIGHT to (0.866f to -0.5f),
            Direction.DOWN_RIGHT to (0.866f to 0.5f),
            Direction.DOWN to (0f to 1f),
            Direction.DOWN_LEFT to (-0.866f to 0.5f),
            Direction.UP_LEFT to (-0.866f to -0.5f),
        )
    }

    private fun paint(rgb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(rgb shr 16, (rgb shr 8) and 0xff, rgb and 0xff) }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val clear = keyboardInsets(insets, density)
        if (clear.left != insetLeft || clear.right != insetRight || clear.bottom != insetBottom) {
            insetLeft = clear.left
            insetRight = clear.right
            insetBottom = clear.bottom
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = build(w, h)

    /** The current layer's geometry; rebuilt when the size or the layer changes. */
    private fun ensureBuilt() {
        if (builtFor !== layout && width > 0) build(width, height)
    }

    private fun build(w: Int, h: Int) {
        builtFor = layout
        val g = HexGrid.fit(
            layout.fitCells,
            left = insetLeft + padding,
            top = padding,
            width = w - insetLeft - insetRight - 2 * padding,
            height = h - insetBottom - 2 * padding,
        )
        grid = g
        touches.span(insetLeft.toFloat(), (w - insetRight).toFloat())
        // Shrinking each hex a little leaves a gap between neighbours.
        paths = layout.keys.filterNot { it.bare }.associateWith { key ->
            Path().apply {
                g.corners(key.pos, scale = 0.94f).forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
        }
        // Keys at the screen edges sit inside the system's back-gesture
        // strips, and a sideways drag from them moves away from the edge
        // exactly like a back swipe.
        if (Build.VERSION.SDK_INT >= 29) {
            val left = insetLeft + padding + 1
            val right = w - insetRight - padding - 1
            systemGestureExclusionRects = layout.keys.mapNotNull { key ->
                val corners = g.corners(key.pos)
                val rect = Rect(
                    corners.minOf { it.x }.toInt().coerceAtLeast(0),
                    corners.minOf { it.y }.toInt(),
                    corners.maxOf { it.x }.toInt().coerceAtMost(w),
                    corners.maxOf { it.y }.toInt(),
                )
                rect.takeIf { it.left <= left || it.right >= right }
            }
        }
        labelPaint.textSize = g.radius * 0.6f
        smallLabelPaint.textSize = g.radius * 0.38f
        hintPaint.textSize = g.radius * 0.3f
        iconPaint.strokeWidth = g.radius * 0.06f
    }

    override fun onDraw(canvas: Canvas) {
        ensureBuilt()
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
                drawFace(canvas, key.face, x, c.y)
            } else {
                drawFace(canvas, key.face, x, c.y - g.radius * 0.45f)
                drawFace(canvas, key.lower, x, c.y + g.radius * 0.45f)
            }
            for ((direction, text) in key.alternates) {
                val (ox, oy) = HINT_OFFSETS.getValue(direction)
                drawCentered(canvas, text, c.x + ox * g.radius * 0.62f, c.y + oy * g.radius * 0.62f, hintPaint)
            }
        }
    }

    private fun fillFor(action: KeyAction) = when (action) {
        KeyAction.Space -> spaceFill
        KeyAction.Enter -> enterFill
        else -> keyFill
    }

    private fun drawFace(canvas: Canvas, face: Face, x: Float, y: Float) {
        when (face.action) {
            KeyAction.Space -> Unit
            KeyAction.Emoji -> drawSmiley(canvas, x, y, labelPaint.textSize * 0.42f)
            // Dim when off, bright for one capital, caps-lock glyph when locked.
            KeyAction.Shift -> {
                labelPaint.alpha = if (keyboard.shift == ShiftState.OFF) 0x80 else 0xff
                drawCentered(canvas, if (keyboard.shift == ShiftState.LOCKED) "⇪" else "⇧", x, y, labelPaint)
                labelPaint.alpha = 0xff
            }
            else -> {
                val label = keyboard.label(face)
                drawCentered(canvas, label, x, y, if (label.length > 1) smallLabelPaint else labelPaint)
            }
        }
    }

    /** Line art in the label color; the emoji font would draw it in yellow. */
    private fun drawSmiley(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, iconPaint)
        val eye = iconPaint.strokeWidth * 0.9f
        iconPaint.style = Paint.Style.FILL
        canvas.drawCircle(x - r * 0.35f, y - r * 0.25f, eye, iconPaint)
        canvas.drawCircle(x + r * 0.35f, y - r * 0.25f, eye, iconPaint)
        iconPaint.style = Paint.Style.STROKE
        canvas.drawArc(RectF(x - r * 0.5f, y - r * 0.45f, x + r * 0.5f, y + r * 0.5f), 25f, 130f, false, iconPaint)
    }

    private fun drawCentered(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val baseline = y - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        ensureBuilt()
        val g = grid ?: return false
        val i = event.actionIndex
        val id = event.getPointerId(i)
        when (event.actionMasked) {
            // The key under a finger at touch down is the one that acts; the
            // release point only decides tap or swipe direction.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val others = (0 until event.pointerCount)
                    .filter { it != i }
                    .associate { event.getPointerId(it) to Point(event.getX(it), event.getY(it)) }
                val key = layout.keyAt(g, event.getX(i), event.getY(i))
                touches.down(id, key, event.getX(i), event.getY(i), others).forEach(::handle)
            }
            // Batched samples first, so drags see the finger's real speed.
            MotionEvent.ACTION_MOVE -> for (p in 0 until event.pointerCount) {
                val pid = event.getPointerId(p)
                for (h in 0 until event.historySize) {
                    touches.move(pid, event.getHistoricalX(p, h), event.getHistoricalY(p, h), event.getHistoricalEventTime(h))
                        .forEach(::handle)
                }
                touches.move(pid, event.getX(p), event.getY(p), event.eventTime).forEach(::handle)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                touches.up(id, event.getX(i), event.getY(i)).forEach(::handle)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> touches.cancel().forEach(::handle)
        }
        keepTicking()
        return true
    }

    private fun handle(e: TouchEvent) {
        when (e) {
            is TouchEvent.Press -> keyboard.resolve(e.key, e.gesture)?.let(onAction)
            is TouchEvent.Act -> onAction(e.action)
        }
        invalidate()
    }

    override fun performClick(): Boolean = super.performClick()
}
