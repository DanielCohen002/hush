package com.djcohen.hush

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
import kotlin.random.Random

/**
 * Hangman: guess the word from its clue by tapping letters on the keyboard drawn underneath.
 * Six wrong guesses draw the whole figure and end the round; Hint reveals one letter for free.
 * Either way the finished board shows an overlay, and a tap asks for the next word.
 */
class HangmanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var word = ""
    private var clueText = ""
    private val guessed = HashSet<Char>()
    private var misses = 0
    private var endedAt = 0L
    private var onSolved: (() -> Unit)? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val clue = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.themeColor(R.attr.pBreath)
    private val ink = context.themeColor(R.attr.pTextPrimary)
    private val dim = context.themeColor(R.attr.pTextDim)
    private val bg = context.themeColor(R.attr.pNight)
    private val wrong = context.getColor(R.color.status_warn)
    private val wash = (accent and 0x00FFFFFF) or 0x33000000
    private val wrongWash = (wrong and 0x00FFFFFF) or 0x33000000

    private val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")

    fun load(puzzle: PuzzleLibrary.Hangman, onSolved: () -> Unit) {
        word = puzzle.word.uppercase()
        clueText = puzzle.clue
        guessed.clear()
        misses = 0
        endedAt = 0L
        this.onSolved = onSolved
        requestLayout()
        invalidate()
    }

    private fun won() = word.isNotEmpty() && word.all { it in guessed }
    private fun lost() = misses >= MAX_MISSES
    private fun over() = won() || lost()

    private fun figureH() = width * 0.45f
    private fun wordH() = width * 0.17f
    private fun clueH() = width * 0.16f
    private fun keyW() = width / 10f
    private fun hintH() = keyW() * 0.85f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * (0.45f + 0.17f + 0.16f + 0.3f + 0.085f) + 8).toInt())
    }

    private fun baseline(centerY: Float) = centerY - (text.ascent() + text.descent()) / 2

    override fun onDraw(canvas: Canvas) {
        if (word.isEmpty()) return
        val w = width.toFloat()
        val d = resources.displayMetrics.density
        val fh = figureH()

        // Gallows, then one body part per wrong guess.
        stroke.strokeWidth = 3f * d
        stroke.color = dim
        val poleX = w * 0.38f
        val ropeX = w * 0.62f
        canvas.drawLine(w * 0.28f, fh * 0.95f, w * 0.72f, fh * 0.95f, stroke)
        canvas.drawLine(poleX, fh * 0.95f, poleX, fh * 0.05f, stroke)
        canvas.drawLine(poleX, fh * 0.05f, ropeX, fh * 0.05f, stroke)
        canvas.drawLine(ropeX, fh * 0.05f, ropeX, fh * 0.15f, stroke)
        stroke.color = if (lost()) wrong else ink
        val r = fh * 0.09f
        val headY = fh * 0.15f + r
        val neckY = headY + r
        val hipY = neckY + fh * 0.27f
        if (misses >= 1) canvas.drawCircle(ropeX, headY, r, stroke)
        if (misses >= 2) canvas.drawLine(ropeX, neckY, ropeX, hipY, stroke)
        if (misses >= 3) canvas.drawLine(ropeX, neckY + fh * 0.05f, ropeX - w * 0.08f, neckY + fh * 0.18f, stroke)
        if (misses >= 4) canvas.drawLine(ropeX, neckY + fh * 0.05f, ropeX + w * 0.08f, neckY + fh * 0.18f, stroke)
        if (misses >= 5) canvas.drawLine(ropeX, hipY, ropeX - w * 0.07f, hipY + fh * 0.25f, stroke)
        if (misses >= 6) canvas.drawLine(ropeX, hipY, ropeX + w * 0.07f, hipY + fh * 0.25f, stroke)

        // The word: a letter or a blank per position (the whole word once the round is lost).
        val slot = minOf(w * 0.12f, w * 0.92f / word.length)
        val left = (w - slot * word.length) / 2f
        val wy = fh + wordH() / 2f
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = slot * 0.7f
        underlines(canvas, left, slot, fh + wordH() * 0.86f, d)
        for ((i, ch) in word.withIndex()) {
            val shown = ch in guessed
            if (!shown && !lost()) continue
            text.color = if (shown) ink else wrong
            canvas.drawText(ch.toString(), left + (i + 0.5f) * slot, baseline(wy - slot * 0.05f), text)
        }

        // The clue.
        clue.color = ink
        clue.textSize = w * 0.053f
        val layout = StaticLayout(clueText, clue, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        canvas.save()
        canvas.translate(0f, fh + wordH() + (clueH() - layout.height.coerceAtMost(clueH().toInt())) / 2f)
        layout.draw(canvas)
        canvas.restore()

        // Keyboard: used letters are tinted (in the word / not) and stay disabled.
        val kw = keyW()
        val top = fh + wordH() + clueH()
        text.typeface = Typeface.DEFAULT
        text.textSize = kw * 0.6f
        for ((ri, row) in rows.withIndex()) {
            val left0 = (width - row.length * kw) / 2f
            for ((ci, ch) in row.withIndex()) {
                val l = left0 + ci * kw
                val t = top + ri * kw
                val used = ch in guessed
                fill.color = if (!used || ch in word) wash else wrongWash
                canvas.drawRoundRect(l + 2 * d, t + 2 * d, l + kw - 2 * d, t + kw - 2 * d, 6 * d, 6 * d, fill)
                text.color = if (!used) ink else if (ch in word) dim else wrong
                canvas.drawText(ch.toString(), l + kw / 2, baseline(t + kw / 2), text)
            }
        }

        // Hint button: reveal one letter, no penalty.
        val hintTop = top + 3 * kw
        fill.color = wash
        canvas.drawRoundRect(2 * d, hintTop + 2 * d, w - 2 * d, hintTop + hintH() - 2 * d, 6 * d, 6 * d, fill)
        text.color = accent
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = kw * 0.4f
        canvas.drawText(context.getString(R.string.crossword_hint), w / 2f, baseline(hintTop + hintH() / 2), text)

        if (over()) {
            if (endedAt == 0L) endedAt = SystemClock.uptimeMillis()
            val a = ((SystemClock.uptimeMillis() - endedAt) / 350f).coerceIn(0f, 1f)
            fill.color = (bg and 0x00FFFFFF) or ((0xE6 * a).toInt() shl 24)
            canvas.drawRect(0f, 0f, w, height.toFloat(), fill)
            val col = if (won()) accent else wrong
            text.color = (col and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = w * 0.075f
            val msg = if (won()) context.getString(R.string.puzzle_solved)
            else context.getString(R.string.hangman_lost, word)
            canvas.drawText(msg, w / 2f, baseline(height / 2f), text)
            text.color = (dim and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
            text.typeface = Typeface.DEFAULT
            text.textSize = w * 0.045f
            canvas.drawText(context.getString(R.string.puzzle_tap_new), w / 2f, baseline(height / 2f + w * 0.09f), text)
            if (a < 1f) postInvalidateOnAnimation()
        }
    }

    /** One underline per letter of the word. */
    private fun underlines(canvas: Canvas, left: Float, slot: Float, y: Float, d: Float) {
        stroke.strokeWidth = 2f * d
        stroke.color = dim
        for (i in word.indices) {
            canvas.drawLine(left + i * slot + slot * 0.12f, y, left + (i + 1) * slot - slot * 0.12f, y, stroke)
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
        if (over()) {
            onSolved?.invoke()
            return
        }
        val kw = keyW()
        val top = figureH() + wordH() + clueH()
        if (y < top) return
        val ri = ((y - top) / kw).toInt()
        if (ri == 3) {
            val hidden = word.filter { it !in guessed }.toSet().toList()
            if (hidden.isNotEmpty()) guessed.add(hidden[Random.nextInt(hidden.size)])
        } else {
            if (ri !in rows.indices) return
            val row = rows[ri]
            val ci = ((x - (width - row.length * kw) / 2f) / kw).toInt()
            if (ci !in row.indices) return
            val ch = row[ci]
            if (ch in guessed) return
            guessed.add(ch)
            if (ch !in word) misses++
        }
        invalidate()
    }

    private companion object {
        const val MAX_MISSES = 6
    }
}
