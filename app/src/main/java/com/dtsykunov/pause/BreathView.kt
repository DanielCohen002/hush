package com.dtsykunov.pause

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Draws the breathing animation for a [BreathStyle]. Time-driven (one looping animator), so
 * restarting after a rotation just picks the cycle up from the start.
 */
class BreathView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.overlay_circle)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.overlay_circle)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val ease = AccelerateDecelerateInterpolator()
    private val box = RectF()

    private var style = BreathStyle.CLASSIC
    private var animator: ValueAnimator? = null
    private var cycleMs = 1L
    private var t = 0L // ms into the current cycle
    private var lastPhase = -1

    /** Called with the phase's label resource (0 = none) each time the phase changes. */
    var onPhase: ((Int) -> Unit)? = null

    fun start(style: BreathStyle) {
        stop()
        this.style = style
        lastPhase = -1
        val phases = style.phases
        cycleMs = when (style) {
            BreathStyle.RIPPLE -> 6000L
            else -> phases.sumOf { it.ms }.coerceAtLeast(1L)
        }
        if (style == BreathStyle.STILL) {
            t = 0; invalidate(); return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cycleMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = (animatedFraction * cycleMs).toLong()
                notifyPhase()
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun phaseIndexAndProgress(): Triple<Int, Float, Float> {
        // Returns (phase index, eased progress within it, scale at phase start).
        val phases = style.phases
        var start = 0L
        var prev = phases.last().scale
        for ((i, p) in phases.withIndex()) {
            if (t < start + p.ms) {
                return Triple(i, ease.getInterpolation((t - start).toFloat() / p.ms), prev)
            }
            start += p.ms
            prev = p.scale
        }
        return Triple(phases.lastIndex, 1f, prev)
    }

    private fun notifyPhase() {
        if (style.phases.isEmpty()) return
        val (i, _, _) = phaseIndexAndProgress()
        if (i != lastPhase) {
            lastPhase = i
            onPhase?.invoke(style.phases[i].labelRes)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        when (style) {
            BreathStyle.STILL -> canvas.drawCircle(cx, cy, r * 0.86f, fill)

            BreathStyle.RIPPLE -> {
                // Three rings spreading outward from a steady core, each fading as it grows.
                canvas.drawCircle(cx, cy, r * 0.6f, fill)
                for (k in 0 until 3) {
                    val f = ((t / cycleMs.toFloat()) + k / 3f) % 1f
                    stroke.alpha = ((1f - f) * 200).toInt()
                    canvas.drawCircle(cx, cy, r * (0.6f + 0.4f * ease.getInterpolation(f)), stroke)
                }
            }

            else -> {
                val (i, p, from) = phaseIndexAndProgress()
                val to = style.phases[i].scale
                val scale = from + (to - from) * p
                canvas.drawCircle(cx, cy, r * 0.86f * scale, fill)

                if (style == BreathStyle.BOX) {
                    // A dot travels the square once per cycle, one side per phase.
                    val inset = stroke.strokeWidth
                    box.set(inset, inset, width - inset, height - inset)
                    stroke.alpha = 70
                    canvas.drawRoundRect(box, r * 0.18f, r * 0.18f, stroke)
                    val side = box.width()
                    val d = (t / cycleMs.toFloat()) * 4f * side
                    val (x, y) = when {
                        d < side -> box.left + d to box.top
                        d < 2 * side -> box.right to box.top + (d - side)
                        d < 3 * side -> box.right - (d - 2 * side) to box.bottom
                        else -> box.left to box.bottom - (d - 3 * side)
                    }
                    canvas.drawCircle(x, y, stroke.strokeWidth * 2.2f, fill)
                }
            }
        }
    }
}
