package com.metro.opticalshell.compat

import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.capability.CapabilityState
import com.metro.opticalshell.core.WindowWidthClass

/** The decision the engine reaches for one launch. */
data class LaunchPolicy(
    val mode: LandscapeMode,
    /** Request system launch bounds? Only meaningful where windowing works. */
    val requestBounds: Boolean,
    /** Keep the app's own orientation rather than pushing landscape. */
    val respectAppOrientation: Boolean,
    /** Accept the system's letterbox for a portrait-locked app. */
    val acceptLetterbox: Boolean,
    /** What the user will actually get, stated without optimism. */
    val honestOutcome: String,
    /** Why this policy, for the diagnostics screen. */
    val rationale: String,
)

/**
 * Chooses how to launch an app in `EXPANDED_LANDSCAPE` (TZ §5, P0).
 *
 * The engine's whole purpose is to avoid the brittle "force landscape on
 * everything" approach that TZ §5 opens by rejecting. It is pure: it takes
 * measurements and capability facts and returns a decision, so every branch is
 * unit-testable without a device.
 *
 * Three rules it will not break:
 *
 *  1. **It never claims a window it cannot get.** `MULTI_WINDOW_DESKTOP` is
 *     only ever chosen when [CapabilityGate] reports windowing AVAILABLE.
 *     Otherwise it falls back to `FOCUSED_FULLSCREEN` and says so — the taskbar
 *     and workspace keep working either way (TZ §4.2).
 *  2. **It never forces another app's orientation.** We hold no API to call
 *     `setRequestedOrientation` on someone else's Activity, so a portrait-locked
 *     app gets the system's compatibility path, not a rotation fight (TZ §5.3).
 *  3. **It never promises a tablet layout.** Width is necessary, not
 *     sufficient: an app without an adaptive UI stays phone-shaped however wide
 *     the window is, and the engine says that in [LaunchPolicy.honestOutcome].
 */
object LandscapePolicyEngine {

    fun decide(
        profile: AppCompatibilityProfile,
        requestedMode: LandscapeMode,
        windowWidthDp: Int,
        held: CapabilityGate.HeldPrivileges,
        freeformSupported: Boolean,
    ): LaunchPolicy {
        val widthClass = WindowWidthClass.ofWidthDp(windowWidthDp)

        // Rule 1: windowing must be genuinely available, not merely wanted.
        val windowingState = CapabilityGate.stateFor(
            CapabilityGate.Ids.SET_WINDOWING_MODE,
            held,
            platformSupports = freeformSupported,
        )
        val windowingUsable = windowingState == CapabilityState.AVAILABLE

        val wantsWindow = requestedMode == LandscapeMode.MULTI_WINDOW_DESKTOP ||
            profile.preferredWindowMode == WindowIntent.WINDOWED

        // Class E is never interfered with (TZ §5.3).
        if (profile.observedClass == AppCompatClass.E_PROTECTED_PIPELINE) {
            return LaunchPolicy(
                mode = LandscapeMode.FOCUSED_FULLSCREEN,
                requestBounds = false,
                respectAppOrientation = true,
                acceptLetterbox = false,
                honestOutcome = "Launched with its own constraints untouched.",
                rationale = "Class E (game, camera or protected pipeline): the shell " +
                    "does not interfere with incompatible system pipelines.",
            )
        }

        // Class C: portrait-locked. No rotation fight (Rule 2).
        if (profile.observedClass == AppCompatClass.C_PORTRAIT_LOCKED) {
            return LaunchPolicy(
                mode = LandscapeMode.FOCUSED_FULLSCREEN,
                requestBounds = false,
                respectAppOrientation = true,
                acceptLetterbox = profile.allowLetterboxFallback,
                honestOutcome = if (profile.allowLetterboxFallback) {
                    "Centred portrait canvas, letterboxed. No false landscape."
                } else {
                    "Portrait, as the app requires."
                },
                rationale = "Class C (portrait-locked). We hold no API to change " +
                    "another app's requested orientation, and forcing rotation can " +
                    "cause an Activity restart loop.",
            )
        }

        // Class D: unstable on rotate. Launch in a stable configuration.
        if (profile.observedClass == AppCompatClass.D_UNSTABLE_ON_ROTATE) {
            return LaunchPolicy(
                mode = LandscapeMode.FOCUSED_FULLSCREEN,
                requestBounds = false,
                respectAppOrientation = profile.rotationPolicy != RotationPolicy.ALLOW_RECREATION,
                acceptLetterbox = profile.allowLetterboxFallback,
                honestOutcome = "Launched in a stable configuration to avoid a " +
                    "restart loop. This is a compromise, not a fix.",
                rationale = "Class D (observed unstable on rotation).",
            )
        }

        if (wantsWindow && windowingUsable) {
            return LaunchPolicy(
                mode = LandscapeMode.MULTI_WINDOW_DESKTOP,
                requestBounds = true,
                respectAppOrientation = true,
                acceptLetterbox = false,
                honestOutcome = "Windowed launch requested through the system " +
                    "window manager. Confirm visually that a window appeared.",
                rationale = "Windowing reported available and the user asked for it.",
            )
        }

        if (wantsWindow && !windowingUsable) {
            return LaunchPolicy(
                mode = LandscapeMode.FOCUSED_FULLSCREEN,
                requestBounds = false,
                respectAppOrientation = true,
                acceptLetterbox = profile.allowLetterboxFallback,
                honestOutcome = "Full screen. A window was asked for but this " +
                    "device does not provide windowing; the desktop stays usable " +
                    "as a navigation shell.",
                rationale = when (windowingState) {
                    CapabilityState.PRIVILEGE_REQUIRED ->
                        "Setting a windowing mode needs a shell bridge (L2): " +
                            "ActivityOptions.setLaunchWindowingMode is a hidden API."

                    CapabilityState.UNAVAILABLE ->
                        "The device reports no freeform support."

                    else -> "Windowing is not available here."
                },
            )
        }

        // Plain focused full screen. Say honestly what width buys.
        val outcome = when {
            profile.observedClass == AppCompatClass.A_ADAPTIVE && widthClass != WindowWidthClass.COMPACT ->
                "Full screen at ${windowWidthDp}dp (${widthClass.name}). This app " +
                    "was observed to be adaptive, so an expanded layout is expected."

            profile.observedClass == AppCompatClass.B_RESIZABLE_PHONE_UI ->
                "Full screen at ${windowWidthDp}dp. This app resizes but its design " +
                    "was observed to stay phone-shaped; expect larger, not different."

            widthClass == WindowWidthClass.COMPACT ->
                "Full screen at ${windowWidthDp}dp (COMPACT, under 600dp). Apps will " +
                    "use phone layouts."

            else ->
                "Full screen at ${windowWidthDp}dp (${widthClass.name}). Apps with " +
                    "adaptive layouts may show an expanded UI - unverified for this app."
        }

        return LaunchPolicy(
            mode = LandscapeMode.FOCUSED_FULLSCREEN,
            requestBounds = false,
            respectAppOrientation = profile.rotationPolicy == RotationPolicy.RESPECT_APP,
            acceptLetterbox = profile.allowLetterboxFallback,
            honestOutcome = outcome,
            rationale = if (profile.observedClass == null) {
                "No observation recorded for this app yet, so no claim is made " +
                    "about its layout."
            } else {
                "Class ${profile.observedClass.name}."
            },
        )
    }

    /**
     * Classifies an app from recorded observations (TZ §5.2, §5.3).
     *
     * Returns null when the evidence does not support any class. That is the
     * correct answer far more often than a guess, and it is why
     * [AppCompatibilityProfile.observedClass] is nullable.
     */
    fun classify(observations: List<LayoutObservation>): AppCompatClass? {
        if (observations.isEmpty()) return null

        if (observations.any { it.recreatedOnRotate == true }) {
            return AppCompatClass.D_UNSTABLE_ON_ROTATE
        }

        val wide = observations.filter { it.windowWidthDp >= 600 }
        if (wide.isEmpty()) return null

        // A real layout change, backed by evidence, is the only route to A.
        if (wide.any { it.layoutChanged == true && it.evidence != null }) {
            return AppCompatClass.A_ADAPTIVE
        }
        // An explicit "no change" at width, with evidence, means B.
        if (wide.any { it.layoutChanged == false && it.evidence != null }) {
            return AppCompatClass.B_RESIZABLE_PHONE_UI
        }
        // Observed at width but nothing determined: still unknown.
        return null
    }
}
