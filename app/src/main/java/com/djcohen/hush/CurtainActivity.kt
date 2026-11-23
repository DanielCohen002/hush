package com.djcohen.hush

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle

/**
 * A plain screen in the pause screen's background colour. It is put behind the notification
 * shade while the pause screen steps aside, so when the shade closes you see this (the same
 * colour as the pause screen) instead of the paused app flashing through for a moment.
 * It does nothing else and closes itself as soon as the pause screen is back.
 */
class CurtainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Prefs.theme(this).styleRes)
        super.onCreate(savedInstanceState)
        val night = themeColor(R.attr.pNight)
        window.setBackgroundDrawable(ColorDrawable(night))
        @Suppress("DEPRECATION")
        window.statusBarColor = night
        @Suppress("DEPRECATION")
        window.navigationBarColor = night
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        instance = this
    }

    override fun onResume() {
        super.onResume()
        onShown?.invoke()
        onShown = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Swallow back: closing this must not reveal the paused app.
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: CurtainActivity? = null
        private var onShown: (() -> Unit)? = null

        /** Show the curtain; [shown] runs once it is on screen. */
        fun show(context: Context, shown: () -> Unit) {
            onShown = shown
            try {
                context.startActivity(
                    Intent(context, CurtainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                )
            } catch (_: Exception) {
                onShown = null
                shown() // can't show it: fall back to just hiding the pause screen
            }
        }

        fun close() {
            onShown = null
            instance?.finish()
            instance = null
        }
    }
}
