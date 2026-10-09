package dev.kronberger.hexboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
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
import dev.kronberger.hexboard.core.SKIN_TONES
import dev.kronberger.hexboard.core.Settings
import dev.kronberger.hexboard.core.withTone
import dev.kronberger.hexboard.core.withoutTone
import kotlin.math.abs

/**
 * The emoji panel, swapped in for the keys at their height ([sizeLike]).
 * Group tabs on top, a scrolling grid with the recently used emoji first,
 * and a bottom row to go back to the letters, type a space, or delete.
 * A long press on an emoji with skin tones offers them in a row above it.
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
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
    }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spaceFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private var haptics = true
    private var longPressMs = 0L

    /**
     * Skin tones offered for a long-pressed emoji: [options] in a row above
     * the cell centered at [x] whose top is at [top]; [selected] follows the
     * finger.
     */
    private class TonePicker(val options: List<String>, val x: Float, val top: Float) {
        var selected = 0
    }

    private var picker: TonePicker? = null
    private val openPicker = Runnable { openPicker() }

    init {
        applyPalette(Palette.DARK)
    }

    fun applyPalette(p: Palette) {
        headerPaint.color = p.hint
        labelPaint.color = p.label
        barFill.color = p.key
        spaceFill.color = p.space
        accent.color = p.hint
        pressedFill.color = p.pressed
        setBackgroundColor(p.background)
        invalidate()
    }

    private companion object {
        const val RECENTS_KEY = "recent_emoji"
        const val RECENT_TITLE = "Recent"
        const val COLUMNS = 8
    }

    private fun buildGrid(): EmojiGrid {
        // Always there, even empty, so the first pick does not shift the tabs.
        return EmojiGrid(listOf(EmojiGroup(RECENT_TITLE, recents.emoji)) + catalog.value, COLUMNS)
    }

    /** Called when the panel is shown: picks up recents and starts at the top. */
    fun refresh() {
        val settings = Prefs(prefs)
        haptics = settings[Settings.haptics]
        longPressMs = settings[Settings.longPress].toLong()
        grid = buildGrid()
        scroller.forceFinished(true)
        offset = 0f
        invalidate()
    }

    private val clearance = Clearance(context, "emoji")

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (clearance.take(insets)) applyClearance()
        invalidate()
        return insets
    }

    private fun applyClearance() {
        val clear = clearance.current
        insetLeft = clear.left
        insetRight = clear.right
        insetBottom = clear.bottom
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (clearance.refresh()) applyClearance()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), sizeLike.measuredHeight)
    }

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
            val icon = if (section.name == RECENT_TITLE) "🕘" else section.emoji.firstOrNull() ?: return@forEachIndexed
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

        picker?.let { drawPicker(canvas, it) }
    }

    /** Where [p]'s row of cells sits: its left edge and cell size, kept on screen. */
    private fun pickerGeometry(p: TonePicker): Pair<Float, Float> {
        val cell = rowHeight
        val w = cell * p.options.size
        val left = (p.x - w / 2).coerceIn(contentLeft, (contentLeft + contentWidth - w).coerceAtLeast(contentLeft))
        return left to cell
    }

    private fun drawPicker(canvas: Canvas, p: TonePicker) {
        val (left, cell) = pickerGeometry(p)
        val top = (p.top - cell).coerceAtLeast(0f)
        val radius = 8f * density
        canvas.drawRoundRect(RectF(left, top, left + cell * p.options.size, top + cell), radius, radius, barFill)
        canvas.drawRoundRect(
            RectF(left + p.selected * cell, top, left + (p.selected + 1) * cell, top + cell), radius, radius, pressedFill,
        )
        emojiPaint.textSize = cell * 0.62f
        p.options.forEachIndexed { i, e ->
            canvas.drawText(e, left + (i + 0.5f) * cell, baseline(top + cell / 2, emojiPaint), emojiPaint)
        }
    }

    /** The emoji under the touch-down point, if it has skin tones this font draws, offers them. */
    private fun openPicker() {
        if (dragging || downY !in gridTop..gridBottom) return
        val row = ((downY - gridTop + offset) / rowHeight).toInt()
        val col = ((downX - contentLeft) / rowHeight).toInt()
        val e = withoutTone(grid.at(row, col) ?: return)
        val tones = SKIN_TONES.map { withTone(e, it) }.filter(emojiPaint::hasGlyph)
        if (tones.isEmpty()) return
        val top = gridTop + row * rowHeight - offset
        picker = TonePicker(listOf(e) + tones, contentLeft + (col + 0.5f) * rowHeight, top)
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun pick(e: String) {
        onAction(KeyAction.Text(e))
        // Saved now, shown next time the panel opens, so the grid
        // does not shift under the finger.
        recents.used(e)
        prefs.edit().putString(RECENTS_KEY, recents.serialize()).apply()
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
                if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (event.y in gridTop..gridBottom) postDelayed(openPicker, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                picker?.let { p ->
                    val (left, cell) = pickerGeometry(p)
                    val i = ((event.x - left) / cell).toInt().coerceIn(0, p.options.size - 1)
                    if (i != p.selected) {
                        p.selected = i
                        invalidate()
                    }
                    return true
                }
                velocity?.addMovement(event)
                val inGrid = downY in gridTop..gridBottom
                if (inGrid && !dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    removeCallbacks(openPicker)
                }
                if (dragging) scrollTo(offset - (event.y - lastY))
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(openPicker)
                picker?.let { p ->
                    picker = null
                    pick(p.options[p.selected])
                    invalidate()
                    return true
                }
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
                removeCallbacks(openPicker)
                picker = null
                invalidate()
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
                pick(grid.at(row, col) ?: return)
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
