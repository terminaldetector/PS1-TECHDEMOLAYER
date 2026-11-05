package com.metro.opticalshell.core

import kotlinx.serialization.Serializable

/**
 * Disables a feature that keeps crashing (TZ §12: "automatic deactivation of a
 * problematic feature after a repeated crash, without meddling in system
 * components").
 *
 * The design point: the guard deactivates **our own** optional features. It
 * never touches system state, and it never "repairs" anything outside the app.
 *
 * Pure and time-injected so the whole state machine is testable.
 */
@Serializable
data class CrashLoopState(
    /** featureId -> consecutive crash count. */
    val counts: Map<String, Int> = emptyMap(),
    /** featureId -> when it was disabled. */
    val disabledAtMillis: Map<String, Long> = emptyMap(),
    /** featureId -> the crash message, so the user is told why. */
    val lastReason: Map<String, String> = emptyMap(),
)

object CrashLoopGuard {

    /** Two strikes. One crash can be a fluke; two in a row is a pattern. */
    const val THRESHOLD = 2

    /** Optional features that may be auto-disabled. */
    object Features {
        const val OPTICAL_LAYER = "optical_layer"
        const val WALLPAPER_MASK = "wallpaper_mask"
        const val OVERLAY_TASKBAR = "overlay_taskbar"
        const val NOTIFICATION_BRIDGE = "notification_bridge"
        const val LIVE_TILE_ANIMATION = "live_tile_animation"
        const val DISPLAY_PROFILE = "display_profile"
    }

    /** Features that must never be auto-disabled, because the shell needs them. */
    private val essential = setOf("tile_grid", "app_list", "shell_state")

    fun recordCrash(
        state: CrashLoopState,
        featureId: String,
        reason: String,
        nowMillis: Long,
    ): CrashLoopState {
        if (featureId in essential) return state
        val next = (state.counts[featureId] ?: 0) + 1
        val counts = state.counts + (featureId to next)
        val reasons = state.lastReason + (featureId to reason)
        return if (next >= THRESHOLD) {
            state.copy(
                counts = counts,
                lastReason = reasons,
                disabledAtMillis = state.disabledAtMillis + (featureId to nowMillis),
            )
        } else {
            state.copy(counts = counts, lastReason = reasons)
        }
    }

    /** A clean run clears the streak, so an old fluke does not accumulate. */
    fun recordCleanRun(state: CrashLoopState, featureId: String): CrashLoopState =
        if (state.counts.containsKey(featureId)) {
            state.copy(counts = state.counts - featureId)
        } else {
            state
        }

    fun isDisabled(state: CrashLoopState, featureId: String): Boolean =
        state.disabledAtMillis.containsKey(featureId)

    /** The user can always switch a guarded feature back on explicitly. */
    fun reEnable(state: CrashLoopState, featureId: String): CrashLoopState = state.copy(
        counts = state.counts - featureId,
        disabledAtMillis = state.disabledAtMillis - featureId,
        lastReason = state.lastReason - featureId,
    )

    fun explain(state: CrashLoopState, featureId: String): String? {
        if (!isDisabled(state, featureId)) return null
        val reason = state.lastReason[featureId] ?: "repeated crashes"
        return "\"$featureId\" was switched off automatically after $THRESHOLD " +
            "consecutive failures ($reason). You can turn it back on in settings."
    }

    fun disabledFeatures(state: CrashLoopState): List<String> =
        state.disabledAtMillis.keys.sorted()
}
