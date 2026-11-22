package com.dtsykunov.pause

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An 8x8 word search. Drag across letters (any of the 8 directions, snapped) to select a word;
 * found words are highlighted and struck off the list underneath.
 */
class WordSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val n = 8
    private var grid = ""
    private var words = emptyList<String>()
    private val found = HashSet<String>()
    private val foundCells = HashSet<Int>()
    private var onSolved: (() -> Unit)? = null

    // Current drag: start cell and the snapped end cell (-1 = none).
    private var startCell = -1
    private var endCell = -1

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.themeColor(R.attr.pBreath)
    private val ink = context.themeColor(R.attr.pTextPrimary)
    private val dim = context.themeColor(R.attr.pTextDim)
    private val tint = (accent and 0x00FFFFFF) or 0x55000000
    private val drag = (accent and 0x00FFFFFF) or 0x88000000.toInt()

    fun load(puzzle: PuzzleLibrary.WordSearch, onSolved: () -> Unit) {
        grid = puzzle.grid
        words = puzzle.words
        found.clear()
        foundCells.clear()
        startCell = -1
        endCell = -1
        this.onSolved = onSolved
        invalidate()
    }

    private fun cell() = width / n.toFloat()
    private fun listRows() = (words.size + 1) / 2
    private fun rowH() = cell() * 0.5f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w + w / n.toFloat() * (0.4f + 0.5f * listRows())).toInt())
    }

    private fun baseline(centerY: Float) = centerY - (text.ascent() + text.descent()) / 2

    private fun cellsBetween(a: Int, b: Int): List<Int> {
        val dr = b / n - a / n
        val dc = b % n - a % n
        val steps = max(abs(dr), abs(dc))
        if (steps == 0) return listOf(a)
        val sr = Integer.signum(dr)
        val sc = Integer.signum(dc)
        return (0..steps).map { (a / n + sr * it) * n + (a % n + sc * it) }
    }

    override fun onDraw(canvas: Canvas) {
        if (grid.isEmpty()) return
        val s = cell()
        val w = width.toFloat()

        fill.color = tint
        for (p in foundCells) {
            canvas.drawRoundRect((p % n) * s + 2, (p / n) * s + 2, (p % n + 1) * s - 2, (p / n + 1) * s - 2, s / 3, s / 3, fill)
        }
        if (startCell >= 0 && endCell >= 0) {
            fill.color = drag
            for (p in cellsBetween(startCell, endCell)) {
                canvas.drawRoundRect((p % n) * s + 2, (p / n) * s + 2, (p % n + 1) * s - 2, (p / n + 1) * s - 2, s / 3, s / 3, fill)
            }
        }

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = s * 0.5f
        text.color = ink
        for (p in 0 until n * n) {
            canvas.drawText(grid[p].toString(), (p % n + 0.5f) * s, baseline((p / n + 0.5f) * s), text)
        }

        // Word list: two columns under the grid; found words are struck through and dimmed.
        text.typeface = Typeface.DEFAULT
        text.textSize = s * 0.38f
        for ((i, word) in words.withIndex()) {
            val cx = if (i % 2 == 0) w * 0.25f else w * 0.75f
            val cy = w + s * 0.4f + (i / 2) * rowH() + rowH() / 2
            val done = word in found
            text.color = if (done) dim else ink
            canvas.drawText(word, cx, baseline(cy), text)
            if (done) {
                fill.color = dim
                val half = text.measureText(word) / 2
                canvas.drawRect(cx - half, cy - 1.5f, cx + half, cy + 1.5f, fill)
            }
        }

        if (found.size == words.size) {
            fill.color = (context.themeColor(R.attr.pNight) and 0x00FFFFFF) or 0xE6000000.toInt()
            canvas.drawRect(0f, 0f, w, height.toFloat(), fill)
            text.color = accent
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = s * 0.6f
            canvas.drawText(context.getString(R.string.puzzle_solved), w / 2f, baseline(height / 2f), text)
            text.color = dim
            text.typeface = Typeface.DEFAULT
            text.textSize = s * 0.3f
            canvas.drawText(context.getString(R.string.puzzle_tap_new), w / 2f, baseline(height / 2f + s * 0.6f), text)
        }
    }

    private fun cellAt(x: Float, y: Float): Int {
        val s = cell()
        return (y / s).toInt().coerceIn(0, n - 1) * n + (x / s).toInt().coerceIn(0, n - 1)
    }

    /** End cell for a drag from [startCell] to the finger, snapped to one of 8 directions. */
    private fun snap(x: Float, y: Float): Int {
        val s = cell()
        val r0 = startCell / n
        val c0 = startCell % n
        val dx = x / s - (c0 + 0.5f)
        val dy = y / s - (r0 + 0.5f)
        // Eight compass directions, clockwise from "right" (screen y points down).
        val k = ((atan2(dy, dx) / (Math.PI / 4)).roundToInt() + 8) % 8
        val sc = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)[k]
        val sr = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)[k]
        // Length: how far the finger is along that direction, limited to the grid.
        val along = if (sc != 0 && sr != 0) (abs(dx) + abs(dy)) / 2 else max(abs(dx), abs(dy))
        var steps = along.roundToInt().coerceAtLeast(0)
        while (steps > 0) {
            val r = r0 + sr * steps
            val c = c0 + sc * steps
            if (r in 0 until n && c in 0 until n) return r * n + c
            steps--
        }
        return startCell
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (found.size == words.size) {
            if (e.actionMasked == MotionEvent.ACTION_UP) onSolved?.invoke()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (e.y < width) {
                    startCell = cellAt(e.x, e.y)
                    endCell = startCell
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (startCell >= 0) {
                    endCell = snap(e.x, e.y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                if (startCell >= 0) {
                    val path = cellsBetween(startCell, snap(e.x, e.y))
                    val picked = path.map { grid[it] }.joinToString("")
                    val match = words.firstOrNull { it !in found && (it == picked || it == picked.reversed()) }
                    if (match != null) {
                        found.add(match)
                        foundCells.addAll(path)
                    }
                }
                startCell = -1
                endCell = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                startCell = -1
                endCell = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
