package com.dtsykunov.pause

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A 5x5 mini crossword. The layout is fixed: rows 0, 2, 4 are across answers and columns
 * 0, 2, 4 are down answers; the cells where an odd row meets an odd column are black. Tap a
 * cell to select it (tap again to switch between across and down), then type on the keyboard
 * drawn underneath.
 */
class CrosswordView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val n = 5
    private var solution = CharArray(n * n) { '#' }
    private var entry = CharArray(n * n) { ' ' }
    private var across = emptyList<String>()
    private var down = emptyList<String>()
    private var sel = 0
    private var acrossDir = true
    private var solved = false
    private var solvedAt = 0L
    /** Cells that are given or revealed by a hint: shown in the accent colour and not editable. */
    private var locked = BooleanArray(n * n)
    private var onSolved: (() -> Unit)? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val clue = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.themeColor(R.attr.pBreath)
    private val ink = context.themeColor(R.attr.pTextPrimary)
    private val dim = context.themeColor(R.attr.pTextDim)
    private val bg = context.themeColor(R.attr.pNight)
    private val wrong = context.getColor(R.color.status_warn)
    private val wash = (accent and 0x00FFFFFF) or 0x33000000
    private val cur = (accent and 0x00FFFFFF) or 0x77000000

    private val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM⌫")

    fun load(puzzle: PuzzleLibrary.Crossword, onSolved: () -> Unit) {
        solution = puzzle.grid.toCharArray()
        entry = CharArray(n * n) { if (solution[it] == '#') '#' else ' ' }
        // Start easier: the first letter of every answer is filled in.
        locked = BooleanArray(n * n)
        for (p in intArrayOf(0, 10, 20, 2, 4)) {
            entry[p] = solution[p]
            locked[p] = true
        }
        across = puzzle.across
        down = puzzle.down
        sel = 0
        acrossDir = true
        solved = false
        solvedAt = 0L
        this.onSolved = onSolved
        invalidate()
    }

    private fun cell() = width / n.toFloat()
    private fun clueH() = cell() * 0.95f
    private fun keyW() = width / 10f
    private fun keyH() = keyW() * 1.15f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val s = w / n.toFloat()
        setMeasuredDimension(w, (w + s * 0.95f + (w / 10f) * 1.15f * 4 + 8).toInt())
    }

    private fun baseline(centerY: Float, p: Paint = text) = centerY - (p.ascent() + p.descent()) / 2

    /** Number of the clue for a cell in a direction: 1-3 across rows 0/2/4 and down cols 0/2/4. */
    private fun clueText(): String {
        val r = sel / n
        val c = sel % n
        return if (acrossDir) {
            val i = r / 2 // across answers are rows 0/2/4, numbered 1, 4, 5
            "${listOf(1, 4, 5)[i]} →  ${across[i]}"
        } else {
            val i = c / 2
            "${i + 1} ↓  ${down[i]}"
        }
    }

    /** True if [p] belongs to the current word. */
    private fun inWord(p: Int): Boolean = if (acrossDir) p / n == sel / n else p % n == sel % n

    override fun onDraw(canvas: Canvas) {
        if (solution[0] == '#' && solution.all { it == '#' }) return
        val s = cell()
        val w = width.toFloat()
        val d = resources.displayMetrics.density

        for (p in 0 until n * n) {
            val x = (p % n) * s
            val y = (p / n) * s
            if (solution[p] == '#') {
                fill.color = ink
                canvas.drawRect(x, y, x + s, y + s, fill)
                continue
            }
            if (p == sel) {
                fill.color = cur
                canvas.drawRect(x, y, x + s, y + s, fill)
            } else if (inWord(p)) {
                fill.color = wash
                canvas.drawRect(x, y, x + s, y + s, fill)
            }
        }
        line.color = dim
        line.strokeWidth = 1f * d
        for (i in 0..n) {
            canvas.drawLine(i * s, 0f, i * s, w, line)
            canvas.drawLine(0f, i * s, w, i * s, line)
        }

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = s * 0.55f
        for (p in 0 until n * n) {
            val ch = entry[p]
            if (ch == '#' || ch == ' ') continue
            text.color = when {
                locked[p] -> accent
                ch != solution[p] -> wrong
                else -> ink
            }
            canvas.drawText(ch.toString(), (p % n + 0.5f) * s, baseline((p / n + 0.5f) * s), text)
        }

        // Clue for the current word.
        clue.color = ink
        clue.textSize = s * 0.3f
        val layout = StaticLayout(clueText(), clue, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        canvas.save()
        canvas.translate(0f, w + (clueH() - layout.height.coerceAtMost(clueH().toInt())) / 2f)
        layout.draw(canvas)
        canvas.restore()

        // Keyboard.
        val kw = keyW()
        val kh = keyH()
        val top = w + clueH()
        text.typeface = Typeface.DEFAULT
        text.textSize = kw * 0.5f
        for ((ri, row) in rows.withIndex()) {
            val left0 = (width - row.length * kw) / 2f
            for ((ci, ch) in row.withIndex()) {
                val l = left0 + ci * kw
                val t = top + ri * kh
                fill.color = if (ch == '⌫') (wrong and 0x00FFFFFF) or 0x33000000 else wash
                canvas.drawRoundRect(l + 2 * d, t + 2 * d, l + kw - 2 * d, t + kh - 2 * d, 6 * d, 6 * d, fill)
                text.color = if (ch == '⌫') wrong else ink
                canvas.drawText(ch.toString(), l + kw / 2, baseline(t + kh / 2), text)
            }
        }

        // Hint row: reveal the selected letter, or the whole current word.
        val hintTop = top + 3 * kh
        text.textSize = kw * 0.4f
        for (k in 0..1) {
            val l = k * (w / 2f)
            fill.color = wash
            canvas.drawRoundRect(l + 2 * d, hintTop + 2 * d, l + w / 2f - 2 * d, hintTop + kh - 2 * d, 6 * d, 6 * d, fill)
            text.color = accent
            text.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(
                context.getString(if (k == 0) R.string.crossword_hint else R.string.crossword_reveal),
                l + w / 4f, baseline(hintTop + kh / 2), text,
            )
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
        if (solved) {
            onSolved?.invoke()
            return
        }
        val s = cell()
        if (y < width) {
            val p = (y / s).toInt().coerceIn(0, n - 1) * n + (x / s).toInt().coerceIn(0, n - 1)
            if (solution[p] == '#') return
            if (p == sel) {
                // Cells at an odd row/column belong to only one word; keep the only valid direction.
                val r = p / n
                val c = p % n
                if (r % 2 == 0 && c % 2 == 0) acrossDir = !acrossDir
            } else {
                sel = p
                val r = p / n
                val c = p % n
                if (r % 2 == 1) acrossDir = false else if (c % 2 == 1) acrossDir = true
            }
        } else {
            val kw = keyW()
            val kh = keyH()
            val ri = ((y - width - clueH()) / kh).toInt()
            if (ri == 3) {
                if (x < width / 2f) reveal(listOf(sel)) else reveal(currentWord())
            } else {
                if (ri !in rows.indices) return
                val row = rows[ri]
                val ci = ((x - (width - row.length * kw) / 2f) / kw).toInt()
                if (ci !in row.indices) return
                val ch = row[ci]
                if (ch == '⌫') {
                    if (entry[sel] == ' ' || locked[sel]) step(-1)
                    if (!locked[sel]) entry[sel] = ' '
                } else if (locked[sel]) {
                    step(1)
                } else {
                    entry[sel] = ch
                    step(1)
                }
            }
            checkSolved()
        }
        invalidate()
    }

    /** Cells of the word the selection is in, in the current direction. */
    private fun currentWord(): List<Int> {
        val r = sel / n
        val c = sel % n
        return if (acrossDir) (0 until n).map { r * n + it } else (0 until n).map { it * n + c }
    }

    private fun reveal(cells: List<Int>) {
        for (p in cells) {
            if (solution[p] == '#') continue
            entry[p] = solution[p]
            locked[p] = true
        }
    }

    private fun checkSolved() {
        if (entry.contentEquals(solution)) solved = true
    }

    /** Move to the next/previous open cell in the current direction, if any. */
    private fun step(by: Int) {
        val dr = if (acrossDir) 0 else by
        val dc = if (acrossDir) by else 0
        val r = sel / n + dr
        val c = sel % n + dc
        if (r in 0 until n && c in 0 until n && solution[r * n + c] != '#') sel = r * n + c
    }
}
