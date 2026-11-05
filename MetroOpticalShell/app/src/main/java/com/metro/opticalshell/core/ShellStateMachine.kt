package com.metro.opticalshell.core

/** Everything that can move the shell between presentations. */
sealed interface ShellEvent {
    /** The manual Mobile <-> Expanded switch. Always available (TZ 2.4). */
    data class SetEnvironment(val environment: ShellEnvironment) : ShellEvent

    /** Manual switch, toggling form. */
    data object ToggleEnvironment : ShellEvent

    /** The window changed shape. Reported by the UI layer, never guessed. */
    data class OrientationChanged(val orientation: ShellOrientation) : ShellEvent

    /** A mouse or physical keyboard appeared. Honoured only if opted in. */
    data object PointerDeviceAttached : ShellEvent

    data class SetOpticalProfile(val profileId: String?) : ShellEvent

    data class SetMobileLandscapePolicy(val policy: MobileLandscapePolicy) : ShellEvent

    data class SetAutoExpandOnPointer(val enabled: Boolean) : ShellEvent

    data object DismissExpandedHint : ShellEvent
}

/**
 * Pure reducer for [ShellState] (TZ 2.4).
 *
 * Invariants this function is responsible for, and that ShellStateMachineTest
 * pins down:
 *
 *  1. Rotation NEVER changes [ShellState.environment]. It only changes which
 *     presentation of EXPANDED renders. One environment, two views.
 *  2. The manual environment switch always works, in either orientation.
 *  3. MOBILE + LANDSCAPE keeps the mobile tile surface; it never silently
 *     promotes the user to EXPANDED.
 *  4. Auto-expand on pointer is ignored unless explicitly enabled.
 *  5. No branch here touches tile layout, display density or the app catalog.
 */
object ShellStateMachine {

    fun reduce(state: ShellState, event: ShellEvent): ShellState = when (event) {
        is ShellEvent.SetEnvironment ->
            if (state.environment == event.environment) {
                state
            } else {
                // Moving environments resets the one-shot hint so that it can
                // be shown again later, but keeps every other preference.
                state.copy(
                    environment = event.environment,
                    expandedHintDismissed = false,
                )
            }

        ShellEvent.ToggleEnvironment -> reduce(
            state,
            ShellEvent.SetEnvironment(
                when (state.environment) {
                    ShellEnvironment.MOBILE -> ShellEnvironment.EXPANDED
                    ShellEnvironment.EXPANDED -> ShellEnvironment.MOBILE
                },
            ),
        )

        is ShellEvent.OrientationChanged ->
            if (state.orientation == event.orientation) {
                state
            } else {
                // Invariant 1: environment is untouched.
                state.copy(
                    orientation = event.orientation,
                    // Returning to portrait clears a hint that no longer applies.
                    expandedHintDismissed =
                    if (event.orientation == ShellOrientation.PORTRAIT) false
                    else state.expandedHintDismissed,
                )
            }

        ShellEvent.PointerDeviceAttached ->
            // Invariant 4: opt-in only, and only ever an upgrade to EXPANDED.
            if (state.autoExpandOnPointer && state.environment == ShellEnvironment.MOBILE) {
                reduce(state, ShellEvent.SetEnvironment(ShellEnvironment.EXPANDED))
            } else {
                state
            }

        is ShellEvent.SetOpticalProfile -> state.copy(opticalProfileId = event.profileId)

        is ShellEvent.SetMobileLandscapePolicy -> state.copy(mobileLandscapePolicy = event.policy)

        is ShellEvent.SetAutoExpandOnPointer -> state.copy(autoExpandOnPointer = event.enabled)

        ShellEvent.DismissExpandedHint -> state.copy(expandedHintDismissed = true)
    }
}
