package com.dtsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.dtsykunov.pause.databinding.ItemAltAppBinding
import com.dtsykunov.pause.databinding.OverlayInterventionBinding
import kotlin.math.ceil

/**
 * The full-screen "screen cover" shown over a paused app: a breathing animation (or a small
 * puzzle) plays while a countdown runs, then the choice buttons appear. Apps the user would
 * rather open instead are offered underneath.
 *
 * The layout differs between portrait and landscape, so a rotation re-inflates the view
 * ([onConfigurationChanged]) and carries the countdown/buttons/puzzle state across.
 */
class InterventionOverlay(
    private val service: AccessibilityService,
    private val pausedPackage: String,
    private val appLabel: String,
    private val attempts: Int,
    private val lastOpenedAt: Long?,
    private val seconds: Int,
    private val phrase: String,
    private val showTimer: Boolean,
    private val isReminder: Boolean = false,
    private val onOpenAnyway: () -> Unit,
    private val onClose: () -> Unit,
    private val onOpenAlternative: (String) -> Unit,
) {
    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    // Material widgets require a Material theme on the inflating context; the bare service
    // context has none, so wrap it in the app theme.
    private val themedContext get() = ContextThemeWrapper(service, Prefs.theme(service).styleRes)
    private lateinit var binding: OverlayInterventionBinding
    private lateinit var params: WindowManager.LayoutParams

    var isShowing = false
        private set

    private var timer: CountDownTimer? = null
    private var removed = false
    private var dismissing = false

    // State that has to survive re-inflating the view on rotation.
    private var endAt = 0L // SystemClock.elapsedRealtime() when the countdown finishes
    private var countdownDone = false
    private var puzzleShown = false
    private var hidden = false

    private val breathStyle = Prefs.breathStyle(service)
    private val puzzleEnabled = Prefs.puzzleEnabled(service)

    // The puzzle in play. Chosen the first time it's shown, so opening the pause screen without
    // touching the puzzle doesn't use one up; kept across rotations.
    private var puzzle: Any? = null

    private fun showPuzzle() {
        val p = puzzle ?: PuzzleLibrary.next(service, PuzzleLibrary.concrete(Prefs.puzzleType(service)))
            .also { puzzle = it }
        binding.puzzleView.show(p) {
            puzzle = null
            showPuzzle()
        }
    }

    fun show() {
        endAt = SystemClock.elapsedRealtime() + seconds * 1000L
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        inflateAndAttach()
        isShowing = true

        // Cover the screen instantly so the app behind isn't briefly visible; ease the
        // content in rather than sliding the whole panel up.
        binding.content.alpha = 0f
        binding.content.post {
            binding.content.animate().alpha(1f).setDuration(250).start()
            startCountdown()
        }
    }

    /** Build the view for the current orientation, bind it, and add it to the window. */
    private fun inflateAndAttach() {
        binding = OverlayInterventionBinding.inflate(LayoutInflater.from(themedContext))
        bindTexts()
        bindAlternatives()

        binding.closeButton.setOnClickListener { dismiss(onClose) }
        binding.openButton.setOnClickListener { dismiss(onOpenAnyway) }

        // Let the overlay handle the back key as "not now".
        binding.root.isFocusableInTouchMode = true
        binding.root.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                dismiss(onClose); true
            } else false
        }

        binding.puzzleButton.visibility = if (puzzleEnabled) View.VISIBLE else View.GONE
        binding.puzzleButton.setOnClickListener { setPuzzleShown(!puzzleShown) }
        setPuzzleShown(puzzleShown)

        if (countdownDone) {
            binding.openButton.visibility = View.VISIBLE
            binding.countdownText.text = "0"
        }
        applyHidden()

        wm.addView(binding.root, params)
        binding.root.requestFocus()
        startBreathing()
    }

    /** Re-layout after the screen rotated: swap in the other orientation's layout. */
    fun onConfigurationChanged() {
        if (!isShowing || removed || dismissing) return
        timer?.cancel()
        binding.breathCircle.stop()
        try {
            wm.removeView(binding.root)
        } catch (_: Exception) {
        }
        inflateAndAttach()
        startCountdown()
    }

    /** Take the pause out of the way (and untouchable) while the notification shade is open,
     *  then bring it back. The shade is a system window that opens *behind* an accessibility
     *  overlay, so without this it would be visible-but-unusable. */
    fun setHidden(hide: Boolean) {
        if (hidden == hide || removed) return
        hidden = hide
        if (isShowing) applyHidden()
    }

    private fun applyHidden() {
        binding.root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        params.flags = if (hidden) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (binding.root.isAttachedToWindow) {
            try {
                wm.updateViewLayout(binding.root, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun bindTexts() {
        val ctx = binding.root.context
        // A check-in interrupts an app the user is already in, so "Opening X" / "Open anyway" /
        // "Cancel" all misdescribe the choice, and there's no open attempt to count.
        binding.openingText.text = ctx.getString(
            if (isReminder) R.string.overlay_still_using else R.string.overlay_opening,
            appLabel,
        )
        binding.openButton.text =
            ctx.getString(if (isReminder) R.string.overlay_continue else R.string.overlay_open)
        binding.closeButton.text =
            ctx.getString(if (isReminder) R.string.overlay_leave else R.string.overlay_close)
        binding.attemptsText.visibility = if (isReminder) View.GONE else View.VISIBLE
        val sinceLast = lastOpenedAt?.let { System.currentTimeMillis() - it }?.takeIf { it >= 0 }
        binding.attemptsText.text = when {
            sinceLast != null && attempts <= 1 ->
                ctx.getString(R.string.overlay_last_opened_one, formatAgo(sinceLast))
            sinceLast != null ->
                ctx.getString(R.string.overlay_last_opened_many, formatAgo(sinceLast), attempts)
            attempts <= 1 -> ctx.getString(R.string.overlay_attempts_one)
            else -> ctx.getString(R.string.overlay_attempts_many, attempts)
        }
        binding.countdownText.text = remainingSeconds().toString()
        binding.countdownText.visibility = if (showTimer) View.VISIBLE else View.GONE
        binding.breatheText.text = phrase
    }

    /** The "or open this instead" row: the user's chosen apps (pre-selected defaults until
     *  they change them), minus anything not installed or that is itself paused. */
    private fun bindAlternatives() {
        val ctx = binding.root.context
        val pm = ctx.packageManager
        val apps = Prefs.altPackages(service)
            .filter { it != pausedPackage && !Prefs.isBlocked(service, it) }
            .mapNotNull { pkg ->
                if (AltApps.launchIntent(service, pkg) == null) return@mapNotNull null
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    Triple(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.second.lowercase() }
            .take(AltApps.MAX)

        binding.altSection.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        binding.altRow.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for ((pkg, label, icon) in apps) {
            val item = ItemAltAppBinding.inflate(inflater, binding.altRow, false)
            item.altIcon.setImageDrawable(icon)
            item.altLabel.text = label
            item.root.setOnClickListener { dismiss { onOpenAlternative(pkg) } }
            binding.altRow.addView(item.root)
        }
    }

    private fun setPuzzleShown(show: Boolean) {
        puzzleShown = show && puzzleEnabled
        if (puzzleShown) {
            try {
                showPuzzle()
            } catch (_: Exception) {
                puzzleShown = false // a missing/corrupt pack must never break the pause screen
            }
        }
        binding.puzzleView.visibility = if (puzzleShown) View.VISIBLE else View.GONE
        binding.breathFrame.visibility = if (puzzleShown) View.GONE else View.VISIBLE
        binding.breatheText.visibility = if (puzzleShown) View.GONE else View.VISIBLE
        if (puzzleShown) binding.phaseText.visibility = View.GONE
        binding.puzzleButton.setText(if (puzzleShown) R.string.overlay_back_to_breathing else R.string.overlay_puzzle)
    }

    /** Compact elapsed time for "Last opened … ago": "45s", "10m", "3h", "2d". */
    private fun formatAgo(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            sec < 86400 -> "${sec / 3600}h"
            else -> "${sec / 86400}d"
        }
    }

    private fun startBreathing() {
        val labelsOn = breathStyle.phases.isNotEmpty() && breathStyle != BreathStyle.CLASSIC
        binding.breathCircle.onPhase = { labelRes ->
            if (labelsOn && !puzzleShown) {
                binding.phaseText.visibility = View.VISIBLE
                binding.phaseText.text = if (labelRes == 0) "" else binding.root.context.getString(labelRes)
            }
        }
        binding.breathCircle.start(breathStyle)
    }

    private fun remainingMs() = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    private fun remainingSeconds() = ceil(remainingMs() / 1000.0).toInt().coerceAtLeast(if (countdownDone) 0 else 1)

    private fun startCountdown() {
        if (countdownDone) return
        timer?.cancel()
        val left = remainingMs()
        if (left <= 0) {
            finishCountdown(); return
        }
        timer = object : CountDownTimer(left, 100) {
            override fun onTick(msLeft: Long) {
                binding.countdownText.text = ceil(msLeft / 1000.0).toInt().coerceAtLeast(1).toString()
            }

            override fun onFinish() = finishCountdown()
        }.start()
    }

    private fun finishCountdown() {
        countdownDone = true
        binding.countdownText.text = "0"
        revealButtons()
    }

    private fun revealButtons() {
        binding.openButton.alpha = 0f
        binding.openButton.visibility = View.VISIBLE
        binding.openButton.animate().alpha(1f).setDuration(300).start()
    }

    /** Run [action] (onClose/onOpenAnyway/…) and start the dismiss animation, once only — the
     *  buttons and back key all stay live during the slide-out, so without this guard a fast
     *  double-tap would double-record stats. */
    private fun dismiss(action: () -> Unit) {
        if (dismissing) return
        dismissing = true
        action()
        slideOutAndRemove()
    }

    private fun slideOutAndRemove() {
        timer?.cancel()
        val h = binding.panel.height.toFloat()
        binding.panel.animate()
            .translationY(h)
            .setDuration(360)
            .withEndAction { removeView() }
            .start()
    }

    /** Remove immediately without animation (e.g. when the service is shutting down). */
    fun dismissNow() {
        timer?.cancel()
        removeView()
    }

    private fun removeView() {
        if (removed) return
        removed = true
        isShowing = false
        binding.breathCircle.stop()
        try {
            wm.removeView(binding.root)
        } catch (_: Exception) {
        }
    }
}
