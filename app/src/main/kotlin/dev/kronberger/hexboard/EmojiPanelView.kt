package dev.kronberger.hexboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.OverScroller
import dev.kronberger.hexboard.core.EmojiGrid
import dev.kronberger.hexboard.core.EmojiGroup
import dev.kronberger.hexboard.core.KeyAction
import dev.kronberger.hexboard.core.Recents
import kotlin.math.abs

/**
 * The emoji panel, swapped in for the keys at their height ([sizeLike]).
 * Group tabs on top, a scrolling grid with the recently used emoji first,
 * and a bottom row to go back to the letters, type a space, or delete.
 */
class EmojiPanelView(
    context: Context,
    /** Loaded on first open, not when the keyboard first shows. */
    private val catalog: Lazy<List<EmojiGroup>>,
    private val prefs: SharedPreferences,
    private val sizeLike: View,
    private val onAction: (KeyAction) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val tabsHeight = 44f * density
    private val barHeight = 48f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val recents = Recents.parse(prefs.getString(RECENTS_KEY, null))
    /** Empty until the first [refresh], which runs before the panel is ever shown. */
    private var grid = EmojiGrid(emptyList(), COLUMNS)

    /** How far the grid is scrolled, in px from its top. */
    private var offset = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null

    private var insetLeft = 0
    private var insetRight = 0
    private var insetBottom = 0

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x9a, 0x9a, 0x9a)
        textSize = 13f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
    }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2e, 0x2e, 0x2e) }
    private val spaceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x48, 0x48, 0x48) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x9a, 0x9a, 0x9a) }

    init {
        setBackgroundColor(Color.rgb(0x12, 0x12, 0x12))
    }

    private companion object {
        const val RECENTS_KEY = "recent_emoji"
        const val RECENT_TITLE = "Recent"
        const val COLUMNS = 8
    }

    private fun buildGrid(): EmojiGrid {
        val recent = recents.emoji.takeIf { it.isNotEmpty() }?.let { EmojiGroup(RECENT_TITLE, it) }
        return EmojiGrid(listOfNotNull(recent) + catalog.value, COLUMNS)
    }

    /** Called when the panel is shown: picks up recents and starts at the top. */
    fun refresh() {
        grid = buildGrid()
        scroller.forceFinished(true)
        offset = 0f
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val clear = keyboardInsets(insets, density)
        insetLeft = clear.left
        insetRight = clear.right
        insetBottom = clear.bottom
        invalidate()
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), sizeLike.measuredHeight)

    // Geometry, all derived from the current size.
    private val contentLeft get() = insetLeft.toFloat()
    private val contentWidth get() = (width - insetLeft - insetRight).toFloat()
    private val gridTop get() = tabsHeight
    private val gridBottom get() = height - insetBottom - barHeight
    private val rowHeight get() = contentWidth / COLUMNS
    private val maxOffset get() = (grid.rows.size * rowHeight - (gridBottom - gridTop)).coerceAtLeast(0f)

    private fun scrollTo(px: Float) {
        offset = px.coerceIn(0f, maxOffset)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val row = rowHeight
        if (row <= 0f || grid.sections.isEmpty()) return
        emojiPaint.textSize = row * 0.62f

        canvas.save()
        canvas.clipRect(0f, gridTop, width.toFloat(), gridBottom)
        val first = (offset / row).toInt()
        var y = gridTop + first * row - offset
        var i = first
        while (y < gridBottom && i < grid.rows.size) {
            when (val r = grid.rows[i]) {
                is EmojiGrid.Row.Header ->
                    canvas.drawText(r.title, contentLeft + 12f * density, baseline(y + row * 0.6f, headerPaint), headerPaint)
                is EmojiGrid.Row.Emojis -> r.emoji.forEachIndexed { col, e ->
                    canvas.drawText(e, contentLeft + (col + 0.5f) * row, baseline(y + row / 2, emojiPaint), emojiPaint)
                }
            }
            y += row
            i++
        }
        canvas.restore()

        // Tabs: one per section, its first emoji as the icon, the visible one underlined.
        val tabWidth = contentWidth / grid.sections.size
        val current = grid.sectionAt(first)
        emojiPaint.textSize = tabsHeight * 0.5f
        grid.sections.forEachIndexed { s, section ->
            val cx = contentLeft + (s + 0.5f) * tabWidth
            val icon = if (section.name == RECENT_TITLE) "🕘" else section.emoji.first()
            canvas.drawText(icon, cx, baseline(tabsHeight / 2, emojiPaint), emojiPaint)
            if (s == current) canvas.drawRect(cx - tabWidth * 0.3f, tabsHeight - 3f * density, cx + tabWidth * 0.3f, tabsHeight, accent)
        }

        // Bottom row: back to letters, space, delete.
        val top = gridBottom + 4f * density
        val bottom = height - insetBottom - 4f * density
        val radius = 8f * density
        val (back, space, delete) = barSlots()
        canvas.drawRoundRect(RectF(back.left, top, back.right, bottom), radius, radius, barFill)
        canvas.drawRoundRect(RectF(space.left, top, space.right, bottom), radius, radius, spaceFill)
        canvas.drawRoundRect(RectF(delete.left, top, delete.right, bottom), radius, radius, barFill)
        val mid = (top + bottom) / 2
        canvas.drawText("ABC", back.centerX(), baseline(mid, labelPaint), labelPaint)
        canvas.drawText("⌫", delete.centerX(), baseline(mid, labelPaint), labelPaint)
    }

    /** Horizontal extents of the bottom row's three buttons, with gaps. */
    private fun barSlots(): Triple<RectF, RectF, RectF> {
        val gap = 4f * density
        val unit = contentWidth / 5
        fun slot(from: Float, to: Float) = RectF(contentLeft + from * unit + gap, 0f, contentLeft + to * unit - gap, 0f)
        return Triple(slot(0f, 1f), slot(1f, 4f), slot(4f, 5f))
    }

    private fun baseline(centerY: Float, paint: Paint) = centerY - (paint.ascent() + paint.descent()) / 2f

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currY.toFloat())
            postInvalidateOnAnimation()
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val inGrid = downY in gridTop..gridBottom
                if (inGrid && !dragging && abs(event.y - downY) > touchSlop) dragging = true
                if (dragging) scrollTo(offset - (event.y - lastY))
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    scroller.fling(0, offset.toInt(), 0, -vy.toInt(), 0, 0, 0, maxOffset.toInt())
                    postInvalidateOnAnimation()
                } else {
                    tap(downX, downY)
                    performClick()
                }
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun tap(x: Float, y: Float) {
        when {
            y < gridTop -> {
                val tab = ((x - contentLeft) / (contentWidth / grid.sections.size)).toInt()
                if (tab in grid.sections.indices) scrollTo(grid.firstRowOf(tab) * rowHeight)
            }
            y < gridBottom -> {
                val row = ((y - gridTop + offset) / rowHeight).toInt()
                val col = ((x - contentLeft) / rowHeight).toInt()
                val e = grid.at(row, col) ?: return
                onAction(KeyAction.Text(e))
                // Saved now, shown next time the panel opens, so the grid
                // does not shift under the finger.
                recents.used(e)
                prefs.edit().putString(RECENTS_KEY, recents.serialize()).apply()
            }
            y < height - insetBottom -> {
                val (back, space, _) = barSlots()
                onAction(
                    when {
                        x < back.right -> KeyAction.Letters
                        x < space.right -> KeyAction.Space
                        else -> KeyAction.Delete
                    },
                )
            }
        }
    }
}
