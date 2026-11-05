package com.metro.opticalshell.gesture

import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import com.metro.opticalshell.core.Presentation

/**
 * Applies the gesture priority contract to a real window (TZ §7.2).
 *
 * This is the safety-critical piece of OP02. Three things it deliberately does
 * NOT do, each forbidden by TZ §7.2:
 *
 *  - it does not place a full-screen overlay to intercept touches;
 *  - it does not simulate Back or Home from synthesised touches;
 *  - it does not request exclusion beyond the platform's permitted limits.
 *
 * It only ever asks for small strips, computed by
 * [GesturePriorityContract.exclusionRectsFor], which clips them out of the
 * mandatory system region and truncates them to the 200dp cap.
 */
@Composable
fun rememberGestureGeometry(imeVisible: Boolean): GestureGeometry {
    val view = LocalView.current
    val density = LocalDensity.current

    val insets = remember(view, imeVisible) {
        val root = view.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
        val gestures = root?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val mandatory = root?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        GestureGeometry(
            windowHeightPx = view.height,
            windowWidthPx = view.width,
            mandatoryBottomPx = mandatory?.bottom ?: 0,
            mandatoryTopPx = mandatory?.top ?: 0,
            systemGestureLeftPx = gestures?.left ?: 0,
            systemGestureRightPx = gestures?.right ?: 0,
            imeVisible = imeVisible,
        )
    }
    // Touch density so a configuration change recomposes this.
    @Suppress("UNUSED_EXPRESSION")
    density
    return insets
}

/**
 * Installs the exclusion rects on the real [View], the only place Android
 * accepts them.
 *
 * Done in a DisposableEffect so the rects are cleared when the composable
 * leaves: a stale exclusion that outlives its screen would quietly steal Back
 * forever, which is exactly the regression TZ §7 treats as blocking.
 */
@Composable
fun GestureExclusionEffect(
    presentation: Presentation,
    geometry: GestureGeometry,
    densityDpi: Int,
    topPx: Int,
    bottomPx: Int,
    enabled: Boolean = true,
) {
    val view: View = LocalView.current

    DisposableEffect(presentation, geometry, topPx, bottomPx, enabled, densityDpi) {
        val allowed = enabled &&
            GesturePriorityContract.allowsHorizontalShellSwipe(presentation)

        val rects = if (!allowed) {
            emptyList()
        } else {
            GesturePriorityContract.exclusionRectsFor(
                geometry = geometry,
                densityDpi = densityDpi,
                requests = listOf(
                    ExclusionRequest(
                        priority = GesturePriority.SHELL_NAVIGATION,
                        rect = ExclusionRect(topPx, bottomPx),
                        reason = "Start to All Apps swipe",
                    ),
                ),
            )
        }

        // Gesture exclusion arrived in API 29. Below that there is no gesture
        // navigation to exclude from, so there is nothing to do and nothing to
        // break (minSdk is 26).
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        if (supported) {
            view.systemGestureExclusionRects = rects.map {
                android.graphics.Rect(0, it.top, view.width, it.bottom)
            }
        }

        onDispose {
            // Always give the gestures back.
            if (supported) view.systemGestureExclusionRects = emptyList()
        }
    }
}

/**
 * Predictive-back-compatible handling (TZ §7.2).
 *
 * Uses the AndroidX [BackHandler], which participates in the platform's
 * predictive back animation, instead of overriding `onBackPressed` or
 * synthesising a Back press. [enabled] must be false whenever the shell has
 * nothing to consume, so Back falls through to the system and the user can
 * always leave.
 */
@Composable
fun ShellBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

/** The shell's own Back stack depth, for deciding whether to consume Back. */
class ShellBackStack {
    private val stack = ArrayDeque<String>()

    val depth: Int get() = stack.size
    val canGoBack: Boolean get() = stack.isNotEmpty()

    fun push(route: String) {
        if (stack.lastOrNull() != route) stack.addLast(route)
    }

    fun pop(): String? = stack.removeLastOrNull()

    fun peek(): String? = stack.lastOrNull()

    fun clear() = stack.clear()
}
