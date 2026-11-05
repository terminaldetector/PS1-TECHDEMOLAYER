package com.metro.opticalshell.gesture

import com.metro.opticalshell.core.Presentation

/**
 * The gesture priority contract of TZ §7.2:
 *
 *   system mandatory > accessibility/security > system bars >
 *   shell navigation > tile interactions > decorative
 *
 * This is the project's blocking acceptance criterion (TZ §7): a shell that
 * breaks Back/Home/Recents makes the device worse, so the rule is encoded here
 * as arithmetic and pinned by tests rather than left to careful coding.
 */
enum class GesturePriority {
    SYSTEM_MANDATORY,
    ACCESSIBILITY_SECURITY,
    SYSTEM_BARS,
    SHELL_NAVIGATION,
    TILE_INTERACTION,
    DECORATIVE,
    ;

    /** Lower ordinal wins. */
    fun outranks(other: GesturePriority): Boolean = ordinal < other.ordinal
}

/** A vertical strip of the screen edge, in pixels from the top. */
data class ExclusionRect(val top: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    fun overlaps(other: ExclusionRect): Boolean = top < other.bottom && other.top < bottom
}

/**
 * The measured gesture geometry of the current window.
 *
 * @param mandatoryBottomPx `WindowInsets.Type.mandatorySystemGestures()` bottom:
 *   the home/recents strip, which the system never yields.
 * @param systemGestureLeftPx / [systemGestureRightPx] `systemGestures()` side
 *   insets: the Back regions.
 */
data class GestureGeometry(
    val windowHeightPx: Int,
    val windowWidthPx: Int,
    val mandatoryBottomPx: Int,
    val mandatoryTopPx: Int,
    val systemGestureLeftPx: Int,
    val systemGestureRightPx: Int,
    val imeVisible: Boolean = false,
)

/**
 * Computes what the shell may legally claim (TZ §7.2).
 *
 * Android caps `setSystemGestureExclusionRects` at 200dp of vertical extent per
 * edge and ignores the excess. Rather than hand the platform rects it will
 * silently drop, this allocates within the cap explicitly, so the behaviour is
 * predictable and testable.
 */
object GesturePriorityContract {

    /** Android's documented per-edge exclusion limit. */
    const val MAX_EXCLUSION_DP = 200

    /**
     * Whether the shell may claim a horizontal swipe at all in this
     * presentation.
     *
     * Portrait Metro genuinely needs the horizontal Start ↔ All Apps swipe
     * (TZ §3.5). The desktop does not: its Start panel is opened by a button,
     * and claiming edge swipes there would fight Back for no benefit.
     */
    fun allowsHorizontalShellSwipe(presentation: Presentation): Boolean =
        presentation.isTileSurface

    /**
     * The exclusion rects the shell may request, in priority order.
     *
     * Rules, all tested:
     *  1. Never intersect the mandatory system gesture strip. The returned
     *     rects are clipped above [GestureGeometry.mandatoryBottomPx].
     *  2. Never exceed the platform's 200dp per-edge cap. Requests are
     *     truncated, highest priority first.
     *  3. Claim nothing at all while the IME is up — the user needs Back more
     *     than we need a swipe (TZ §7.1 item 10).
     *  4. An empty list is a valid, safe answer.
     */
    fun exclusionRectsFor(
        geometry: GestureGeometry,
        densityDpi: Int,
        requests: List<ExclusionRequest>,
    ): List<ExclusionRect> {
        if (geometry.imeVisible) return emptyList()

        val capPx = (MAX_EXCLUSION_DP * densityDpi / 160.0).toInt()

        // The region the system owns at the bottom, plus anything at the top.
        val usableTop = geometry.mandatoryTopPx.coerceAtLeast(0)
        val usableBottom = (geometry.windowHeightPx - geometry.mandatoryBottomPx)
            .coerceAtLeast(usableTop)

        var budget = capPx
        val granted = ArrayList<ExclusionRect>()

        for (request in requests.sortedBy { it.priority.ordinal }) {
            if (budget <= 0) break
            // Rule 1: clip into the region the system does not own.
            val top = request.rect.top.coerceIn(usableTop, usableBottom)
            val bottom = request.rect.bottom.coerceIn(usableTop, usableBottom)
            val clipped = ExclusionRect(top, bottom)
            if (clipped.height <= 0) continue

            // Rule 2: truncate to the remaining budget.
            val allowedHeight = minOf(clipped.height, budget)
            val final = ExclusionRect(clipped.top, clipped.top + allowedHeight)
            granted.add(final)
            budget -= final.height
        }

        return granted
    }

    /**
     * Whether a shell gesture starting at [yPx] would collide with a system
     * gesture. Used to refuse a tile drag that begins in the system's strip
     * (TZ §7.1 item 8: a drag must not start on a Back/Home swipe).
     */
    fun collidesWithSystemGesture(geometry: GestureGeometry, xPx: Int, yPx: Int): Boolean {
        val inBottomStrip = yPx >= geometry.windowHeightPx - geometry.mandatoryBottomPx
        val inTopStrip = yPx <= geometry.mandatoryTopPx
        val inLeftStrip = xPx <= geometry.systemGestureLeftPx
        val inRightStrip = xPx >= geometry.windowWidthPx - geometry.systemGestureRightPx
        return inBottomStrip || inTopStrip || inLeftStrip || inRightStrip
    }

    /**
     * The honest navigation status to show the user (TZ §7.2, §OP02-E).
     *
     * The shell must say which situation it is in rather than mask a
     * downgrade — the spec reports confirmed cases of HyperOS forcing 3-button
     * navigation when a third-party Home is assigned.
     */
    fun navigationStatus(
        isDefaultHome: Boolean,
        gestureNavigationActive: Boolean,
    ): NavigationStatus = when {
        !isDefaultHome && gestureNavigationActive ->
            NavigationStatus.SYSTEM_GESTURES_INTACT

        !isDefaultHome && !gestureNavigationActive ->
            NavigationStatus.BUTTON_NAVIGATION_SYSTEM_CHOICE

        isDefaultHome && gestureNavigationActive ->
            NavigationStatus.SYSTEM_GESTURES_INTACT_AS_HOME

        else -> NavigationStatus.DOWNGRADED_BY_OEM
    }
}

/** One request for an exclusion strip. */
data class ExclusionRequest(
    val priority: GesturePriority,
    val rect: ExclusionRect,
    val reason: String,
)

enum class NavigationStatus {
    /** Running as a normal app; the stock launcher still owns gestures. */
    SYSTEM_GESTURES_INTACT,

    /** The device is on button navigation, not because of us. */
    BUTTON_NAVIGATION_SYSTEM_CHOICE,

    /** We are Home and gestures still work. The good case. */
    SYSTEM_GESTURES_INTACT_AS_HOME,

    /**
     * We are Home and gesture navigation is gone. TZ §7.3 calls this a
     * blocking condition: it must be reported, and the user offered a one-step
     * way back.
     */
    DOWNGRADED_BY_OEM,
    ;

    val isBlocking: Boolean get() = this == DOWNGRADED_BY_OEM

    val message: String
        get() = when (this) {
            SYSTEM_GESTURES_INTACT ->
                "System navigation is intact. Metro runs as an ordinary app and the " +
                    "stock launcher still provides gestures."

            BUTTON_NAVIGATION_SYSTEM_CHOICE ->
                "This device is using button navigation. That is the system's " +
                    "current setting, not something the shell changed."

            SYSTEM_GESTURES_INTACT_AS_HOME ->
                "Metro is the default Home app and system gestures still work."

            DOWNGRADED_BY_OEM ->
                "Metro is the default Home app and gesture navigation is no longer " +
                    "active. The OEM downgraded navigation when Home changed. Revert " +
                    "Metro as Home to restore it."
        }
}
