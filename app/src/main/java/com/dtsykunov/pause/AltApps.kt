package com.dtsykunov.pause

import android.content.Context
import android.content.Intent

/** Apps offered on the pause screen as a better thing to do than the one being paused. */
object AltApps {
    /** Most apps the pause screen shows; keeps the row on one line in portrait. */
    const val MAX = 4

    /** Pre-selected when installed and the user hasn't picked their own: reading, notes,
     *  learning, audio. First [MAX] installed ones win, in this order. */
    val SUGGESTED = listOf(
        "com.amazon.kindle",
        "com.google.android.apps.books",
        "com.overdrive.mobile.android.libby",
        "com.audible.application",
        "com.duolingo",
        "com.google.android.keep",
        "md.obsidian",
        "au.com.shiftyjelly.pocketcasts",
        "de.danoeh.antennapod",
        "org.mozilla.firefox",
    )

    fun installedDefaults(c: Context): Set<String> =
        SUGGESTED.filter { c.packageManager.getLaunchIntentForPackage(it) != null }
            .take(MAX).toSet()

    /** The launch intent for [pkg], or null if it isn't installed/launchable. */
    fun launchIntent(c: Context, pkg: String): Intent? =
        c.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
