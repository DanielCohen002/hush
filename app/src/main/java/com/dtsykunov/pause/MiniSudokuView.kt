package com.dtsykunov.pause

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Plays a 6x6 sudoku (2x3 boxes) with a number pad row underneath (1-6, erase). Drawn as one
 * view so it needs no extra layout and sizes itself from its width.
 */
class MiniSudokuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var n = 6
    private var boxR = 2
    private var boxC = 3
    private var given = IntArray(n * n)
    private var solution = IntArray(n * n)
    private var cells = IntArray(n * n)
    private var selected = -1
    private var solved = false
    private var solvedAt = 0L
    private var onSolved: (() -> Unit)? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.themeColor(R.attr.pBreath)
    private val ink = context.themeColor(R.attr.pTextPrimary)
    private val dim = context.themeColor(R.attr.pTextDim)
    private val bg = context.themeColor(R.attr.pNight)
    private val wrong = context.getColor(R.color.status_warn)
    private val tint = (accent and 0x00FFFFFF) or 0x33000000
    private val wrongTint = (wrong and 0x00FFFFFF) or 0x33000000

    /** [onSolved] is called when the board is completed, to let the host offer a new puzzle. */
    fun load(puzzle: PuzzleLibrary.Sudoku, onSolved: () -> Unit) {
        n = Math.round(Math.sqrt(puzzle.given.size.toDouble())).toInt()
        boxR = 2
        boxC = n / 2 // 4x4 has 2x2 boxes, 6x6 has 2x3
        given = puzzle.given
        solution = puzzle.solution
        cells = given.copyOf()
        selected = -1
        solved = false
        solvedAt = 0L
        this.onSolved = onSolved
        requestLayout()
        invalidate()
    }

    private fun cell() = width / n.toFloat()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        // Grid is square; the pad adds one more cell-height row plus a small gap.
        setMeasuredDimension(w, (w + w / n.toFloat() * 1.25f).toInt())
    }

    private fun baseline(centerY: Float) = centerY - (text.ascent() + text.descent()) / 2

    override fun onDraw(canvas: Canvas) {
        val s = cell()
        val w = width.toFloat()
        val d = resources.displayMetrics.density

        // The selected cell's row and column get a light wash.
        if (selected >= 0) {
            fill.color = tint
            val sr = selected / n
            val sc = selected % n
            canvas.drawRect(0f, sr * s, w, (sr + 1) * s, fill)
            canvas.drawRect(sc * s, 0f, (sc + 1) * s, w, fill)
        }

        // Grid lines: thin per cell, thick on box borders (boxes are 2 rows x 3 columns).
        for (i in 0..n) {
            val thick = i % boxC == 0
            line.color = if (thick) ink else dim
            line.strokeWidth = (if (thick) 2f else 1f) * d
            canvas.drawLine(i * s, 0f, i * s, w, line)
        }
        for (i in 0..n) {
            val thick = i % boxR == 0
            line.color = if (thick) ink else dim
            line.strokeWidth = (if (thick) 2f else 1f) * d
            canvas.drawLine(0f, i * s, w, i * s, line)
        }

        text.textSize = s * 0.55f
        for (p in 0 until n * n) {
            val v = cells[p]
            if (v == 0) continue
            val isGiven = given[p] != 0
            text.typeface = if (isGiven) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.color = when {
                isGiven -> ink
                v != solution[p] -> wrong
                else -> accent
            }
            canvas.drawText(v.toString(), (p % n + 0.5f) * s, baseline((p / n + 0.5f) * s), text)
        }

        // Number pad: 1..6 and an erase cell, seven across.
        val padTop = w + s * 0.25f
        val pw = w / (n + 1f)
        text.textSize = pw * 0.5f
        text.typeface = Typeface.DEFAULT
        for (k in 0..n) {
            val left = k * pw
            fill.color = if (k < n) tint else wrongTint
            canvas.drawRoundRect(left + 2 * d, padTop, left + pw - 2 * d, padTop + s, 8 * d, 8 * d, fill)
            text.color = if (k < n) ink else wrong
            val label = if (k < n) (k + 1).toString() else "⌫"
            canvas.drawText(label, left + pw / 2, baseline(padTop + s / 2), text)
        }

        if (solved) {
            if (solvedAt == 0L) solvedAt = SystemClock.uptimeMillis()
            val a = ((SystemClock.uptimeMillis() - solvedAt) / 350f).coerceIn(0f, 1f)
            fill.color = (bg and 0x00FFFFFF) or ((0xE6 * a).toInt() shl 24)
            canvas.drawRect(0f, 0f, w, w, fill)
            text.color = (accent and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = s * 0.6f
            canvas.drawText(context.getString(R.string.puzzle_solved), w / 2f, baseline(w / 2f), text)
            text.color = (dim and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            text.typeface = Typeface.DEFAULT
            text.textSize = s * 0.3f
            canvas.drawText(context.getString(R.string.puzzle_tap_new), w / 2f, baseline(w / 2f + s * 0.6f), text)
            if (a < 1f) postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the enclosing ScrollView from stealing taps on the board.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                handleTap(e.x, e.y)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleTap(x: Float, y: Float) {
        val s = cell()
        if (solved) {
            onSolved?.invoke()
            return
        }
        if (y < width) {
            val p = (y / s).toInt().coerceIn(0, n - 1) * n + (x / s).toInt().coerceIn(0, n - 1)
            selected = if (given[p] == 0) p else -1
        } else if (y >= width + s * 0.25f && selected >= 0) {
            val k = (x / (width / (n + 1f))).toInt().coerceIn(0, n)
            cells[selected] = if (k < n) k + 1 else 0
            if (cells.contentEquals(solution)) {
                solved = true
                selected = -1
            }
        }
        invalidate()
    }
}
