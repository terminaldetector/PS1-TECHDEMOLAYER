package com.metro.opticalshell.core

/**
 * Turns a measured window into a [ShellOrientation] (TZ 2.4).
 *
 * The shell reads the window it actually has, rather than the sensor: a window
 * is landscape when it is wider than it is tall. That keeps the rule true in
 * split screen and in a freeform window, where the sensor says nothing useful.
 */
object OrientationPolicy {

    fun orientationOf(widthDp: Int, heightDp: Int): ShellOrientation =
        if (widthDp > heightDp) ShellOrientation.LANDSCAPE else ShellOrientation.PORTRAIT

    /**
     * How many tile columns the grid should use.
     *
     * WP8 phones used 4 half-columns (i.e. 4 "small" tiles) across. We keep 4
     * for compact widths and widen with the window, so EXPANDED_PORTRAIT is the
     * same tile system with more columns, not a different launcher (TZ 2.2).
     *
     * [scale] is the user's shell tile scale; a larger scale means fewer, bigger
     * columns, and it never changes the system density (TZ 4.1).
     */
    fun tileColumnsFor(
        presentation: Presentation,
        widthDp: Int,
        scale: Float = 1f,
    ): Int {
        val effective = (widthDp / scale.coerceIn(0.5f, 2.0f)).toInt()
        val raw = when (presentation) {
            Presentation.MOBILE_PORTRAIT -> when {
                effective < 360 -> 4
                effective < 480 -> 4
                else -> 6
            }

            Presentation.EXPANDED_PORTRAIT -> when {
                effective < 480 -> 4
                effective < 600 -> 6
                effective < 840 -> 8
                else -> 10
            }

            // The desktop's Start panel is narrower than the whole screen.
            Presentation.EXPANDED_LANDSCAPE -> when {
                effective < 720 -> 6
                effective < 1000 -> 8
                else -> 10
            }
        }
        // Keep columns even: every tile size except 1x1 is 2 units wide.
        return if (raw % 2 == 0) raw else raw + 1
    }
}
