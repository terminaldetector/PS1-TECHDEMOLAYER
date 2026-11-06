package com.metro.opticalshell.gesture

import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
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
/**
 * Reads the live gesture geometry (FIX-06).
 *
 * The previous version keyed a `remember` on the `View` instance, which is the
 * SAME object across a rotation — so `view.height`, `view.width` and the insets
 * it had captured went stale exactly when they mattered. Everything here now
 * comes from state-backed Compose sources that recompose on their own:
 *
 *  - `WindowInsets.systemGestures` / `mandatorySystemGestures` update when the
 *    system bars, cutout or navigation mode change;
 *  - `WindowInsets.ime` updates when the keyboard appears;
 *  - `LocalConfiguration.screenWidthDp/screenHeightDp` update on rotation, on
 *    a density change, and on a split-screen or freeform resize, because the
 *    configuration describes the current WINDOW, not the physical panel.
 *
 * So the returned geometry is correct after rotation, an IME change, a density
 * change and a split/freeform resize, which is what FIX-06 asked for.
 */
@Composable
fun rememberGestureGeometry(): GestureGeometry {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    val gestures = WindowInsets.systemGestures
    val mandatory = WindowInsets.mandatorySystemGestures
    val ime = WindowInsets.ime

    // Reading each inset inside the composition is what subscribes us to it.
    val imeBottomPx = ime.getBottom(density)
    val mandatoryBottomPx = mandatory.getBottom(density)
    val mandatoryTopPx = mandatory.getTop(density)
    val gestureLeftPx = gestures.getLeft(density, LayoutDirection.Ltr)
    val gestureRightPx = gestures.getRight(density, LayoutDirection.Ltr)

    // Configuration is in dp and reflects the current window, so converting it
    // with the current density gives live window pixels.
    val widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val heightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }

    return GestureGeometry(
        windowHeightPx = heightPx,
        windowWidthPx = widthPx,
        mandatoryBottomPx = mandatoryBottomPx,
        mandatoryTopPx = mandatoryTopPx,
        systemGestureLeftPx = gestureLeftPx,
        systemGestureRightPx = gestureRightPx,
        imeVisible = imeBottomPx > 0,
    )
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
                android.graphics.Rect(0, it.top, geometry.windowWidthPx, it.bottom)
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
