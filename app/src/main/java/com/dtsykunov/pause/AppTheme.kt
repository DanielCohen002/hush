package com.dtsykunov.pause

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors

/** The user-selectable palettes. Each has light and dark values (see the colors.xml files in res/values and res/values-night). */
enum class AppTheme(val id: String, val labelRes: Int, val styleRes: Int) {
    EMBER("ember", R.string.theme_ember, R.style.Theme_Pause),
    SEAFOAM("teal", R.string.theme_teal, R.style.Theme_Pause_Seafoam),
    LAVENDER("lavender", R.string.theme_lavender, R.style.Theme_Pause_Lavender);

    companion object {
        fun fromId(id: String?): AppTheme = values().firstOrNull { it.id == id } ?: EMBER
    }
}

/** Resolve a palette attribute (e.g. [R.attr.pBreath]) against the context's current theme. */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, 0)
