package com.metro.opticalshell.runtime

import android.app.Activity
import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.capability.CapabilityState
import com.metro.opticalshell.compat.AppCompatibilityProfile
import com.metro.opticalshell.compat.LandscapeMode
import com.metro.opticalshell.compat.LandscapePolicyEngine
import com.metro.opticalshell.compat.LaunchPolicy
import com.metro.opticalshell.compat.WindowIntent
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.display.DisplayReadout

/** Where a launch was initiated from (TZ v4 §4.3). */
enum class LaunchSource {
    TILE, ALL_APPS, DESKTOP_SHORTCUT, TASKBAR, SEARCH, NOTIFICATION, DEEP_SHORTCUT, FOLDER,
}

/** One launch request, whatever surface it came from. */
data class AppLaunchRequest(
    val packageName: String,
    val activityName: String?,
    val source: LaunchSource,
    val presentation: Presentation,
    val requestedMode: LandscapeMode? = null,
)

/**
 * Honest telemetry for one launch (TZ v4 §4.3 step 6, §13).
 *
 * `REQUESTED` means we asked. `OBSERVED` means we measured a result. They are
 * never merged.
 */
enum class LaunchTelemetry { REQUESTED, OBSERVED, FAILED, UNKNOWN }

data class OrchestratedLaunch(
    val request: AppLaunchRequest,
    val policy: LaunchPolicy,
    val outcome: LaunchOutcome,
    val telemetry: LaunchTelemetry,
    /** Which capability decided the mode, and what state it was in. */
    val capabilityChecked: String,
    val capabilityState: CapabilityState,
    val note: String,
)

/**
 * THE single entry point for starting an app (TZ v4 §4.3, FIX-03).
 *
 * The bug this fixes: `ShellViewModel.launch()` called `StandardAppLauncher`
 * directly, so every tile tap, app-list tap and shortcut bypassed
 * `LandscapePolicyEngine` entirely — the engine was unit-tested but *dead code*
 * in the running app. Every surface now routes through [launch], and the
 * per-launch capability check is made for the **specific operation**, not for a
 * general "privilege level" (TZ v4 §4.3 step 4, and FIX-02).
 *
 * The seven steps of §4.3 appear in order below, and the type makes it hard to
 * skip one: nothing but [launch] is public.
 */
class AppLaunchOrchestrator(
    private val standardLauncher: StandardAppLauncher,
    private val freeformAdapter: FreeformLaunchAdapter,
    private val profileProvider: (packageName: String) -> AppCompatibilityProfile,
    private val heldPrivileges: () -> CapabilityGate.HeldPrivileges,
    private val readoutProvider: () -> DisplayReadout?,
    private val freeformSupported: () -> Boolean,
    private val onLaunched: (OrchestratedLaunch) -> Unit,
) {

    fun launch(request: AppLaunchRequest, activity: Activity?): OrchestratedLaunch {
        // 1. Resolve the package and the current configuration.
        val held = heldPrivileges()
        val readout = readoutProvider()

        // 2. Profile plus real measurements.
        val profile = profileProvider(request.packageName)
        val windowWidthDp = readout?.windowWidthDp ?: FALLBACK_WIDTH_DP

        // 3. Decide the mode. Portrait presentations always run full screen;
        //    only EXPANDED_LANDSCAPE has a windowing choice to make.
        val requestedMode = request.requestedMode
            ?: if (request.presentation == Presentation.EXPANDED_LANDSCAPE) {
                when (profile.preferredWindowMode) {
                    WindowIntent.WINDOWED -> LandscapeMode.MULTI_WINDOW_DESKTOP
                    else -> LandscapeMode.FOCUSED_FULLSCREEN
                }
            } else {
                LandscapeMode.FOCUSED_FULLSCREEN
            }

        val policy = LandscapePolicyEngine.decide(
            profile = profile,
            requestedMode = requestedMode,
            windowWidthDp = windowWidthDp,
            held = held,
            freeformSupported = freeformSupported(),
        )

        // 4. Check the capability for THIS operation, not a tier in general.
        val capabilityId = if (policy.mode == LandscapeMode.MULTI_WINDOW_DESKTOP) {
            CapabilityGate.Ids.SET_WINDOWING_MODE
        } else {
            CapabilityGate.Ids.LAUNCH_APP
        }
        val capabilityState = CapabilityGate.stateFor(
            capabilityId,
            held,
            platformSupports = if (policy.mode == LandscapeMode.MULTI_WINDOW_DESKTOP) {
                freeformSupported()
            } else {
                null
            },
        )

        // 5. Perform the real request through the appropriate adapter.
        val windowed = policy.mode == LandscapeMode.MULTI_WINDOW_DESKTOP &&
            capabilityState == CapabilityState.AVAILABLE &&
            policy.requestBounds &&
            activity != null

        val outcome = if (windowed) {
            freeformAdapter.launchWindowed(
                activity = activity!!,
                packageName = request.packageName,
                activityName = request.activityName,
            )
        } else {
            // 7. The fallback is a plain full-screen start, which always works
            //    and changes no user data.
            standardLauncher.launch(request.packageName, request.activityName)
        }

        // 6. Record REQUESTED; only a later measurement may say OBSERVED.
        val telemetry = when (outcome) {
            is LaunchOutcome.Failed -> LaunchTelemetry.FAILED
            is LaunchOutcome.Started -> LaunchTelemetry.REQUESTED
        }

        val note = buildString {
            append(policy.honestOutcome)
            if (windowed) {
                append(" A windowed launch was REQUESTED; whether a window appeared ")
                append("must be confirmed by looking at the screen.")
            } else if (policy.mode == LandscapeMode.MULTI_WINDOW_DESKTOP) {
                append(" Fell back to full screen: ")
                append(policy.rationale)
            }
        }

        val result = OrchestratedLaunch(
            request = request,
            policy = policy,
            outcome = outcome,
            telemetry = telemetry,
            capabilityChecked = capabilityId,
            capabilityState = capabilityState,
            note = note,
        )
        onLaunched(result)
        return result
    }

    companion object {
        /**
         * Used only when no measurement is available yet. Deliberately a
         * compact width, so an unmeasured launch is never treated as wide.
         */
        const val FALLBACK_WIDTH_DP = 411
    }
}
