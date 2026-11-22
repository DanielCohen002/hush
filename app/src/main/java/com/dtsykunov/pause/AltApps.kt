package com.dtsykunov.pause

import android.content.Context
import android.content.Intent

/** Helpers for the apps offered on the pause screen as a better thing to do (user-chosen). */
object AltApps {
    /** Most apps the pause screen shows; keeps the row on one line in portrait. */
    const val MAX = 4

    /** The launch intent for [pkg], or null if it isn't installed/launchable. */
    fun launchIntent(c: Context, pkg: String): Intent? =
        c.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
