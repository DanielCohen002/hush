package com.djcohen.hush

/**
 * The breathing animations the pause screen can show. A style is a repeating list of
 * [Phase]s; [BreathView] draws them. [phases] is empty for styles that don't count breaths.
 */
enum class BreathStyle(val id: String, val labelRes: Int) {
    CLASSIC("classic", R.string.breath_classic),
    BOX("box", R.string.breath_box),
    RIPPLE("ripple", R.string.breath_ripple),
    STILL("still", R.string.breath_still);

    /** One leg of the cycle: grow/shrink to [scale] over [ms], showing [labelRes] meanwhile. */
    data class Phase(val labelRes: Int, val ms: Long, val scale: Float)

    val phases: List<Phase>
        get() = when (this) {
            CLASSIC -> listOf(
                Phase(0, 5000, 1f),
                Phase(0, 5000, 0.72f),
            )
            BOX -> listOf(
                Phase(R.string.phase_in, 4000, 1f),
                Phase(R.string.phase_hold, 4000, 1f),
                Phase(R.string.phase_out, 4000, 0.72f),
                Phase(R.string.phase_hold, 4000, 0.72f),
            )
            RIPPLE, STILL -> emptyList()
        }

    companion object {
        fun fromId(id: String?): BreathStyle = values().firstOrNull { it.id == id } ?: CLASSIC
    }
}
