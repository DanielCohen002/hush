package com.djcohen.hush

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.djcohen.hush.databinding.ItemAltAppBinding
import com.djcohen.hush.databinding.OverlayInterventionBinding
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

    // Keep YouTube from playing behind the pause: while it's up, pause any playback that
    // starts, and resume only if we were the ones who paused it and the user proceeds.
    private val audio = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private val holdMedia = pausedPackage.contains("youtube") && !pausedPackage.contains("music")
    private var pausedMedia = false
    private val mediaHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // A pause key alone only works when the media key reaches the right session at the right
    // moment, which is why the old hold missed sometimes. Also take audio focus: a player that
    // loses focus pauses itself, with no routing involved. Focus is requested again whenever
    // playback is seen, because the app can grab it back when it starts late.
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { }
    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun takeFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = focusRequest ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, mediaHandler)
                .build()
                .also { focusRequest = it }
            audio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun dropFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(focusListener)
        }
    }

    private var holdTicks = 0
    private val mediaHold = object : Runnable {
        override fun run() {
            if (removed || dismissing) return
            if (audio.isMusicActive) {
                takeFocus()
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                pausedMedia = true
            } else if (holdTicks++ % 5 == 0) {
                // Not playing right now; keep focus anyway so a late start yields to us.
                takeFocus()
            }
            mediaHandler.postDelayed(this, 250)
        }
    }

    private fun sendMediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun stopMediaHold(resume: Boolean) {
        mediaHandler.removeCallbacks(mediaHold)
        if (holdMedia) dropFocus()
        if (resume && pausedMedia) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        pausedMedia = false
    }

    private val breathStyle = Prefs.breathStyle(service)
    private val puzzleEnabled = Prefs.puzzleEnabled(service)

    // The puzzle in play. Chosen the first time it's shown, so opening the pause screen without
    // touching the puzzle doesn't use one up; kept across rotations.
    private var puzzle: Any? = null

    private fun showPuzzle() {
        val p = puzzle ?: PuzzleLibrary.next(service, Prefs.puzzleTypes(service).random())
            .also { puzzle = it }
        binding.puzzleView.show(p) {
            puzzle = null
            showPuzzle()
        }
        binding.puzzleView.alpha = 0f
        binding.puzzleView.animate().alpha(1f).setDuration(220).start()
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
        if (holdMedia) mediaHandler.post(mediaHold)

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

        binding.closeButton.setOnClickListener { dismiss(action = onClose) }
        binding.topOpenButton.setOnClickListener { dismiss(resumeMedia = true, action = onOpenAnyway) }

        // A swipe down from the top edge opens the notification shade (the overlay covers the
        // status bar, so the system's own gesture can't reach it). The shade-hiding logic in the
        // service then moves the pause out of the way.
        var downY = 0f
        var opened = false
        binding.shadeGrabber.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = ev.rawY; opened = false }
                android.view.MotionEvent.ACTION_MOVE -> if (!opened && ev.rawY - downY > 40 * service.resources.displayMetrics.density) {
                    opened = true
                    openShade()
                }
            }
            true
        }

        // Let the overlay handle the back key as "not now".
        binding.root.isFocusableInTouchMode = true
        binding.root.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                dismiss(action = onClose); true
            } else false
        }

        binding.puzzleButton.visibility = if (puzzleEnabled) View.VISIBLE else View.GONE
        binding.puzzleButton.setOnClickListener { setPuzzleShown(!puzzleShown) }
        setPuzzleShown(puzzleShown)

        if (countdownDone) binding.countdownText.text = "0"
        updateCornerTimer()
        applyHidden()

        wm.addView(binding.root, params)
        addCatcher()
        binding.root.requestFocus()
        startBreathing()
    }

    /** Re-layout after the screen rotated: swap in the other orientation's layout. */
    fun onConfigurationChanged() {
        if (!isShowing || removed || dismissing) return
        timer?.cancel()
        binding.breathCircle.stop()
        removeCatcher()
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
    // A thin transparent window over the status bar. A pull-down that starts there would
    // otherwise reach the system and open the shade invisibly behind the pause screen (this
    // overlay starts below the status bar); here it opens the shade ourselves instead.
    private var catcher: View? = null
    private lateinit var catcherParams: WindowManager.LayoutParams

    private fun addCatcher() {
        removeCatcher()
        val res = service.resources
        val h = topBarHeightPx()
        val v = View(service)
        var downY = 0f
        var opened = false
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = ev.rawY; opened = false }
                android.view.MotionEvent.ACTION_MOVE ->
                    if (!opened && ev.rawY - downY > 24 * res.displayMetrics.density) {
                        opened = true
                        openShade()
                    }
            }
            true
        }
        catcherParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // IN_SCREEN + NO_LIMITS: sit over the status bar itself, not below it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            // Notched / punch-hole phones: let the strip extend into the cutout area too.
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(v, catcherParams)
            catcher = v
        } catch (_: Exception) {
        }
    }

    /** Height of whatever sits at the top of this phone's screen (status bar, cutout), so the
     *  strip fits any device and orientation instead of assuming one fixed size. */
    private fun topBarHeightPx(): Int {
        val res = service.resources
        val density = res.displayMetrics.density
        var h = 0
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
                )
                h = insets.top
            } catch (_: Exception) {
            }
        }
        if (h <= 0) {
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) h = res.getDimensionPixelSize(id)
        }
        // Never absurdly thin (some skins report 0 in landscape) or tall.
        return h.coerceIn((24 * density).toInt(), (64 * density).toInt())
    }

    private fun removeCatcher() {
        catcher?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        catcher = null
    }

    fun setHidden(hide: Boolean) {
        if (hidden == hide || removed) return
        hidden = hide
        if (!isShowing) return
        applyHidden()
        // The cover screen stays up for the rest of this pause (it closes when you proceed or
        // leave). Closing it right away let the paused app's own open animation slide across
        // the status bar, which the pause screen doesn't cover.
    }

    /** Open the notification shade over a plain cover screen, so that closing it never shows
     *  the paused app (starting an activity closes the shade, so the cover goes first). */
    private fun openShade() {
        CurtainActivity.show(service) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    private fun applyHidden() {
        binding.root.animate().cancel()
        if (hidden) {
            binding.root.alpha = 1f
            binding.root.visibility = View.INVISIBLE
        } else {
            // Come back instantly opaque so the paused app never shows through as the shade closes.
            binding.root.alpha = 1f
            binding.root.visibility = View.VISIBLE
        }
        catcher?.let { c ->
            catcherParams.flags = if (hidden) {
                catcherParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                catcherParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            try {
                wm.updateViewLayout(c, catcherParams)
            } catch (_: Exception) {
            }
        }
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
        binding.topOpenButton.text =
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
        binding.breatheText.visibility = if (puzzleShown || phrase.isBlank()) View.GONE else View.VISIBLE
        if (puzzleShown) binding.phaseText.visibility = View.GONE
        updateCornerTimer()
        if (puzzleShown) fitPuzzle()
        binding.puzzleButton.setText(if (puzzleShown) R.string.overlay_back_to_breathing else R.string.overlay_puzzle)
    }

    /** Give the puzzle exactly the height left over, so the pause screen never needs to scroll. */
    private fun fitPuzzle() {
        binding.root.post {
            if (!puzzleShown) return@post
            val content = binding.content
            val wrapper = binding.puzzleView.parent as? View
            var other = content.paddingTop + content.paddingBottom
            if (content.indexOfChild(wrapper) >= 0) {
                for (i in 0 until content.childCount) {
                    val c = content.getChildAt(i)
                    if (c.visibility == View.GONE) continue
                    val lp = c.layoutParams as ViewGroup.MarginLayoutParams
                    other += lp.topMargin + lp.bottomMargin
                    if (c !== wrapper) other += c.height
                }
            }
            val margin = (8 * binding.root.resources.displayMetrics.density).toInt()
            binding.puzzleView.budgetPx = (binding.scrollView.height - other - margin).coerceAtLeast(0)
        }
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
        binding.breathCircle.start(breathStyle, seconds * 1000L)
    }

    /** While a puzzle replaces the breathing circle (and its countdown), keep the time left
     *  visible in the corner, whether or not the on-circle timer is turned on. */
    private fun updateCornerTimer() {
        val show = puzzleShown && !countdownDone
        binding.cornerTimer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) binding.cornerTimer.text = remainingSeconds().toString()
        // "Open anyway" lives in the same top slot as the timer chip in every mode, invisible
        // (but holding its space) until time's up, so nothing shifts when it appears.
        binding.topOpenButton.visibility = if (countdownDone) View.VISIBLE else View.INVISIBLE
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
                updateCornerTimer()
            }

            override fun onFinish() = finishCountdown()
        }.start()
    }

    private fun finishCountdown() {
        countdownDone = true
        binding.countdownText.text = "0"
        updateCornerTimer()
        revealButtons()
    }

    private fun revealButtons() {
        updateCornerTimer()
        binding.topOpenButton.alpha = 0f
        binding.topOpenButton.animate().alpha(1f).setDuration(300).start()
    }

    /** Run [action] (onClose/onOpenAnyway/…) and start the dismiss animation, once only — the
     *  buttons and back key all stay live during the slide-out, so without this guard a fast
     *  double-tap would double-record stats. */
    private fun dismiss(resumeMedia: Boolean = false, action: () -> Unit) {
        if (dismissing) return
        dismissing = true
        // Bring the paused app back underneath while the pause screen slides away.
        CurtainActivity.close()
        stopMediaHold(resumeMedia)
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

    /** The user left (home/recents/another app): stop taking touches and fade out quickly, which
     *  looks far less abrupt than the view vanishing. */
    fun dismissFade() {
        if (removed || dismissing) return
        dismissing = true
        stopMediaHold(false)
        timer?.cancel()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.updateViewLayout(binding.root, params)
        } catch (_: Exception) {
        }
        binding.root.animate().alpha(0f).setDuration(140).withEndAction { removeView() }.start()
    }

    /** Remove immediately without animation (e.g. when the service is shutting down). */
    fun dismissNow() {
        stopMediaHold(false)
        timer?.cancel()
        removeView()
    }

    private fun removeView() {
        if (removed) return
        removed = true
        isShowing = false
        CurtainActivity.close()
        removeCatcher()
        binding.breathCircle.stop()
        try {
            wm.removeView(binding.root)
        } catch (_: Exception) {
        }
    }
}
