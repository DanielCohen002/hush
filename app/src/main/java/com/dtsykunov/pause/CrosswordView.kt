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
 * A mini crossword on any small grid shape. Words are the runs of 3+ white cells (the same rule
 * tools/gen_puzzles.py uses), numbered in reading order. Tap a cell to select it (tap again to
 * switch between across and down where both exist), then type on the keyboard drawn underneath.
 * Nothing is filled in to start; Hint reveals the selected letter and Reveal word the whole word.
 */
class CrosswordView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var n = 5
    private var solution = CharArray(n * n) { '#' }
    private var entry = CharArray(n * n) { ' ' }
    private var across = emptyList<String>()
    private var down = emptyList<String>()

    // Slots as lists of cell indexes, in the same order as the clue lists.
    private var acrossSlots = emptyList<List<Int>>()
    private var downSlots = emptyList<List<Int>>()
    private var acrossAt = IntArray(0) // cell -> across slot index, or -1
    private var downAt = IntArray(0)
    private var numberAt = IntArray(0) // cell -> clue number, or 0

    private var sel = 0
    private var acrossDir = true
    private var solved = false
    private var solvedAt = 0L

    /** Cells revealed by a hint: shown in the accent colour and not editable. */
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
        n = Math.round(Math.sqrt(puzzle.grid.length.toDouble())).toInt()
        solution = puzzle.grid.toCharArray()
        entry = CharArray(n * n) { if (solution[it] == '#') '#' else ' ' }
        locked = BooleanArray(n * n)
        across = puzzle.across
        down = puzzle.down
        buildSlots()
        sel = solution.indexOfFirst { it != '#' }.coerceAtLeast(0)
        acrossDir = acrossAt[sel] >= 0
        solved = false
        solvedAt = 0L
        this.onSolved = onSolved
        requestLayout()
        invalidate()
    }

    /** Words are runs of 3+ white cells, across then down, ordered by their first cell. */
    private fun buildSlots() {
        val a = ArrayList<List<Int>>()
        val d = ArrayList<List<Int>>()
        for (r in 0 until n) {
            var run = ArrayList<Int>()
            for (c in 0..n) {
                if (c < n && solution[r * n + c] != '#') {
                    run.add(r * n + c)
                } else {
                    if (run.size >= 3) a.add(run)
                    run = ArrayList()
                }
            }
        }
        for (c in 0 until n) {
            var run = ArrayList<Int>()
            for (r in 0..n) {
                if (r < n && solution[r * n + c] != '#') {
                    run.add(r * n + c)
                } else {
                    if (run.size >= 3) d.add(run)
                    run = ArrayList()
                }
            }
        }
        acrossSlots = a // already in reading order
        downSlots = d.sortedBy { it.first() } // cell index order == reading order of first cell
        acrossAt = IntArray(n * n) { -1 }
        downAt = IntArray(n * n) { -1 }
        acrossSlots.forEachIndexed { i, s -> s.forEach { acrossAt[it] = i } }
        downSlots.forEachIndexed { i, s -> s.forEach { downAt[it] = i } }
        numberAt = IntArray(n * n)
        val starts = HashSet<Int>().apply {
            acrossSlots.forEach { add(it.first()) }
            downSlots.forEach { add(it.first()) }
        }
        var num = 0
        for (p in 0 until n * n) if (p in starts) numberAt[p] = ++num
    }

    private fun cell() = width / n.toFloat()
    private fun clueH() = width * 0.19f
    private fun keyW() = width / 10f
    private fun keyH() = keyW() * 1.15f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w + w * 0.19f + (w / 10f) * 1.15f * 4 + 8).toInt())
    }

    private fun baseline(centerY: Float, p: Paint = text) = centerY - (p.ascent() + p.descent()) / 2

    private fun clueText(): String {
        val slot = if (acrossDir) acrossAt[sel] else downAt[sel]
        if (slot < 0) return ""
        val first = (if (acrossDir) acrossSlots[slot] else downSlots[slot]).first()
        val body = if (acrossDir) across.getOrElse(slot) { "" } else down.getOrElse(slot) { "" }
        return "${numberAt[first]} ${if (acrossDir) "→" else "↓"}  $body"
    }

    private fun currentWord(): List<Int> =
        (if (acrossDir) acrossSlots.getOrNull(acrossAt[sel]) else downSlots.getOrNull(downAt[sel]))
            ?: listOf(sel)

    override fun onDraw(canvas: Canvas) {
        if (solution.all { it == '#' }) return
        val s = cell()
        val w = width.toFloat()
        val d = resources.displayMetrics.density
        val wordCells = currentWord().toSet()

        for (p in 0 until n * n) {
            val x = (p % n) * s
            val y = (p / n) * s
            if (solution[p] == '#') {
                fill.color = ink
                canvas.drawRect(x, y, x + s, y + s, fill)
            } else if (p == sel) {
                fill.color = cur
                canvas.drawRect(x, y, x + s, y + s, fill)
            } else if (p in wordCells) {
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

        // Clue numbers, small in the corner of the cell.
        text.typeface = Typeface.DEFAULT
        text.textSize = s * 0.24f
        text.textAlign = Paint.Align.LEFT
        text.color = dim
        for (p in 0 until n * n) {
            if (numberAt[p] > 0) {
                canvas.drawText(numberAt[p].toString(), (p % n) * s + s * 0.08f, (p / n) * s + s * 0.28f, text)
            }
        }
        text.textAlign = Paint.Align.CENTER

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
            canvas.drawText(ch.toString(), (p % n + 0.5f) * s, baseline((p / n + 0.5f) * s + s * 0.06f), text)
        }

        // Clue for the current word.
        clue.color = ink
        clue.textSize = w * 0.06f
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
                // Switch direction only where the cell has both an across and a down word.
                if (acrossAt[p] >= 0 && downAt[p] >= 0) acrossDir = !acrossDir
            } else {
                sel = p
                if (acrossAt[p] < 0) acrossDir = false else if (downAt[p] < 0) acrossDir = true
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
            if (entry.contentEquals(solution)) solved = true
        }
        invalidate()
    }

    private fun reveal(cells: List<Int>) {
        for (p in cells) {
            if (solution[p] == '#') continue
            entry[p] = solution[p]
            locked[p] = true
        }
    }

    /** Move to the next/previous cell within the current word, if there is one. */
    private fun step(by: Int) {
        val word = currentWord()
        val i = word.indexOf(sel) + by
        if (i in word.indices) sel = word[i]
    }
}
