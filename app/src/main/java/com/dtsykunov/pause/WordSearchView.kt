package com.dtsykunov.pause

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An 8x8 word search. Drag across letters (any of the 8 directions, snapped) to select a word;
 * the selection is a capsule that glides after your finger, and found words stay circled and
 * are struck off the list underneath.
 */
class WordSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var n = 8
    private var grid = ""
    private var words = emptyList<String>()
    private val found = HashSet<String>()
    private var onSolved: (() -> Unit)? = null
    private var solvedAt = 0L

    /** A found word: first and last cell, and when it was found (for the draw-on animation). */
    private class Found(val a: Int, val b: Int, val t0: Long)
    private val foundPaths = ArrayList<Found>()

    // Current drag: start cell, snapped target (px), and the smoothed point the capsule
    // actually reaches, which eases toward the target each frame.
    private var startCell = -1
    private var targetX = 0f
    private var targetY = 0f
    private var curX = 0f
    private var curY = 0f

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.themeColor(R.attr.pBreath)
    private val ink = context.themeColor(R.attr.pTextPrimary)
    private val dim = context.themeColor(R.attr.pTextDim)
    private val bg = context.themeColor(R.attr.pNight)
    private val rect = RectF()

    fun load(puzzle: PuzzleLibrary.WordSearch, onSolved: () -> Unit) {
        grid = puzzle.grid
        n = Math.round(Math.sqrt(puzzle.grid.length.toDouble())).toInt()
        words = puzzle.words
        found.clear()
        foundPaths.clear()
        startCell = -1
        solvedAt = 0
        this.onSolved = onSolved
        requestLayout()
        invalidate()
    }

    private fun cell() = width / n.toFloat()
    private fun listRows() = (words.size + 1) / 2
    private fun rowH() = cell() * 0.5f
    private fun cx(p: Int) = (p % n + 0.5f) * cell()
    private fun cy(p: Int) = (p / n + 0.5f) * cell()

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

    /** A capsule outline from (x0,y0) to (x1,y1), as if a pen circled the letters. */
    private fun capsule(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
        val rad = cell() * 0.44f
        val len = hypot(x1 - x0, y1 - y0)
        val angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        canvas.save()
        canvas.translate(x0, y0)
        canvas.rotate(angle)
        rect.set(-rad, -rad, len + rad, rad)
        canvas.drawRoundRect(rect, rad, rad, paint)
        canvas.restore()
    }

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    override fun onDraw(canvas: Canvas) {
        if (grid.isEmpty()) return
        val s = cell()
        val w = width.toFloat()
        val d = resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        var animating = false

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = s * 0.5f
        text.color = ink
        for (p in 0 until n * n) {
            canvas.drawText(grid[p].toString(), cx(p), baseline(cy(p)), text)
        }

        // Found words: circled, drawn on over ~250ms the moment they're found.
        ring.strokeWidth = 2.2f * d
        ring.color = accent
        wash.color = (accent and 0x00FFFFFF) or 0x22000000
        for (f in foundPaths) {
            val p = ease(((now - f.t0) / 250f).coerceIn(0f, 1f))
            if (p < 1f) animating = true
            val x1 = cx(f.a) + (cx(f.b) - cx(f.a)) * p
            val y1 = cy(f.a) + (cy(f.b) - cy(f.a)) * p
            capsule(canvas, cx(f.a), cy(f.a), x1, y1, wash.also { it.style = Paint.Style.FILL })
            capsule(canvas, cx(f.a), cy(f.a), x1, y1, ring)
        }

        // The selection in progress: the end glides toward the finger's snapped cell.
        if (startCell >= 0) {
            curX += (targetX - curX) * 0.35f
            curY += (targetY - curY) * 0.35f
            if (abs(targetX - curX) > 0.5f || abs(targetY - curY) > 0.5f) animating = true
            ring.color = (accent and 0x00FFFFFF) or 0xAA000000.toInt()
            capsule(canvas, cx(startCell), cy(startCell), curX, curY, ring)
        }

        // Word list: two columns under the grid; found words are struck through and dimmed.
        text.typeface = Typeface.DEFAULT
        text.textSize = s * 0.38f
        for ((i, word) in words.withIndex()) {
            val colX = if (i % 2 == 0) w * 0.25f else w * 0.75f
            val rowY = w + s * 0.4f + (i / 2) * rowH() + rowH() / 2
            val done = word in found
            text.color = if (done) dim else ink
            canvas.drawText(word, colX, baseline(rowY), text)
            if (done) {
                fill.color = dim
                val half = text.measureText(word) / 2
                canvas.drawRect(colX - half, rowY - 1.5f, colX + half, rowY + 1.5f, fill)
            }
        }

        if (found.size == words.size) {
            if (solvedAt == 0L) solvedAt = now
            val a = ((now - solvedAt) / 350f).coerceIn(0f, 1f)
            if (a < 1f) animating = true
            fill.color = (bg and 0x00FFFFFF) or ((0xE6 * a).toInt() shl 24)
            canvas.drawRect(0f, 0f, w, height.toFloat(), fill)
            text.color = (accent and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = s * 0.6f
            canvas.drawText(context.getString(R.string.puzzle_solved), w / 2f, baseline(height / 2f), text)
            text.color = (dim and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            text.typeface = Typeface.DEFAULT
            text.textSize = s * 0.3f
            canvas.drawText(context.getString(R.string.puzzle_tap_new), w / 2f, baseline(height / 2f + s * 0.6f), text)
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun cellAt(x: Float, y: Float): Int {
        val s = cell()
        return (y / s).toInt().coerceIn(0, n - 1) * n + (x / s).toInt().coerceIn(0, n - 1)
    }

    /** The cell a drag from [startCell] to the finger ends on, snapped to one of 8 directions. */
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

    private fun setTarget(cell: Int) {
        targetX = cx(cell)
        targetY = cy(cell)
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
                    setTarget(startCell)
                    curX = targetX
                    curY = targetY
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (startCell >= 0) {
                    setTarget(snap(e.x, e.y))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                if (startCell >= 0) {
                    val end = snap(e.x, e.y)
                    val picked = cellsBetween(startCell, end).map { grid[it] }.joinToString("")
                    val match = words.firstOrNull { it !in found && (it == picked || it == picked.reversed()) }
                    if (match != null) {
                        found.add(match)
                        foundPaths.add(Found(startCell, end, SystemClock.uptimeMillis()))
                    }
                }
                startCell = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                startCell = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
