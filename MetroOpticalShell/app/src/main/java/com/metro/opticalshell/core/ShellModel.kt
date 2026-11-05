package com.metro.opticalshell.core

import kotlinx.serialization.Serializable

/**
 * TWO functional environments. Not three shells (TZ section 0, 13).
 *
 * MOBILE   - compact ordinary phone mode.
 * EXPANDED - one tablet/desktop working environment with shared state,
 *            apps, settings and logic. Portrait and landscape are two
 *            PRESENTATIONS of this single environment, never two systems.
 */
enum class ShellEnvironment { MOBILE, EXPANDED }

/** Physical orientation of the shell window, as reported by the window size. */
enum class ShellOrientation { PORTRAIT, LANDSCAPE }

/**
 * THREE presentations (TZ section 0 table).
 *
 * Both portrait presentations are Metro tiles. The landscape one is the Metro
 * desktop. Nothing else may replace portrait tiles (TZ section 0, 13).
 */
enum class Presentation {
    MOBILE_PORTRAIT,
    EXPANDED_PORTRAIT,
    EXPANDED_LANDSCAPE,
    ;

    val environment: ShellEnvironment
        get() = when (this) {
            MOBILE_PORTRAIT -> ShellEnvironment.MOBILE
            EXPANDED_PORTRAIT, EXPANDED_LANDSCAPE -> ShellEnvironment.EXPANDED
        }

    /** Portrait presentations are tile surfaces. Landscape is the desktop. */
    val isTileSurface: Boolean
        get() = this == MOBILE_PORTRAIT || this == EXPANDED_PORTRAIT
}

/**
 * Explicit policy for MOBILE + LANDSCAPE (TZ 2.4).
 *
 * The state machine must not lose state and must not silently change the
 * environment, so a landscape phone in MOBILE keeps rendering the mobile Metro
 * surface. The only difference between the two policies is whether the shell
 * offers to move to EXPANDED.
 */
enum class MobileLandscapePolicy {
    /** Keep the mobile tile layout, say nothing. */
    KEEP_PORTRAIT_LAYOUT,

    /** Keep the mobile tile layout and show a dismissible "go Expanded?" hint. */
    OFFER_EXPANDED,
}

/** Which Android adaptive window size class the current window falls into (TZ 4.2). */
enum class WindowWidthClass {
    /** < 600 dp */
    COMPACT,

    /** 600..839 dp */
    MEDIUM,

    /** 840..1199 dp */
    EXPANDED,

    /** >= 1200 dp. Not one of the three classes the TZ lists, kept separate. */
    EXTRA_LARGE,
    ;

    companion object {
        fun ofWidthDp(widthDp: Int): WindowWidthClass = when {
            widthDp < 600 -> COMPACT
            widthDp < 840 -> MEDIUM
            widthDp < 1200 -> EXPANDED
            else -> EXTRA_LARGE
        }
    }
}

/**
 * The whole shell state (TZ section 8). Serialisable, orientation-independent
 * where it matters: tile layout never lives here.
 */
@Serializable
data class ShellState(
    val environment: ShellEnvironment = ShellEnvironment.MOBILE,
    val orientation: ShellOrientation = ShellOrientation.PORTRAIT,
    val opticalProfileId: String? = null,
    val mobileLandscapePolicy: MobileLandscapePolicy = MobileLandscapePolicy.OFFER_EXPANDED,
    /** TZ 2.4: auto-switch on mouse/keyboard is a setting, OFF by default. */
    val autoExpandOnPointer: Boolean = false,
    /** True while the "go Expanded?" hint is showing and not yet dismissed. */
    val expandedHintDismissed: Boolean = false,
) {
    /**
     * The single source of truth for which of the three presentations renders.
     *
     * MOBILE + LANDSCAPE deliberately resolves to MOBILE_PORTRAIT: tiles stay
     * tiles and the environment is never switched behind the user's back.
     */
    val presentation: Presentation
        get() = when (environment) {
            ShellEnvironment.MOBILE -> Presentation.MOBILE_PORTRAIT
            ShellEnvironment.EXPANDED -> when (orientation) {
                ShellOrientation.PORTRAIT -> Presentation.EXPANDED_PORTRAIT
                ShellOrientation.LANDSCAPE -> Presentation.EXPANDED_LANDSCAPE
            }
        }

    /** Whether the shell should offer the move to EXPANDED right now. */
    val showExpandedHint: Boolean
        get() = environment == ShellEnvironment.MOBILE &&
            orientation == ShellOrientation.LANDSCAPE &&
            mobileLandscapePolicy == MobileLandscapePolicy.OFFER_EXPANDED &&
            !expandedHintDismissed
}
