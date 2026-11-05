package com.metro.opticalshell.display

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.Serializable

/**
 * What this build can actually do to the SYSTEM display configuration.
 *
 * TZ 4.3 splits this into tiers and TZ 13 forbids pretending. So does this
 * file: nothing here ever reports success it did not observe.
 */
enum class BridgeTier {
    /** Tier 0. Read-only. The shipped default. */
    NO_PRIVILEGE,

    /**
     * Tier 1. The density change is performed by an OPERATOR over ADB, outside
     * the app. The app computes the commands and verifies the result by
     * re-reading metrics. It does not and cannot run them itself.
     */
    EXTERNAL_ADB_PROCEDURE,

    /**
     * Tier 2. A privileged adapter (Shizuku / ADB bridge / platform signature)
     * is present and can apply the change in-process.
     *
     * No implementation ships in Operation 01 (TZ 4.3 Tier 2). The interface
     * exists so that adding one later does not require touching the UI.
     */
    PRIVILEGED_ADAPTER,
}

/** Why a bridge cannot act, in words fit to show a user. */
data class BridgeUnavailableReason(val code: String, val detail: String)

sealed interface ApplyResult {
    /** The bridge refused. It changed nothing. */
    data class Refused(val reason: BridgeUnavailableReason) : ApplyResult

    /**
     * The change cannot be made in-process, but here is the exact operator
     * procedure. Still a refusal as far as the app is concerned.
     */
    data class ManualProcedureRequired(
        val applyCommands: List<String>,
        val restoreCommands: List<String>,
        val note: String,
    ) : ApplyResult

    /**
     * The bridge issued the change AND a follow-up read confirmed it.
     * Only a verified read may produce this.
     */
    data class Applied(val observedDensityDpi: Int, val observedWidthDp: Int) : ApplyResult
}

/** The persisted record of what we changed, so it can be put back (TZ 4.3). */
@Serializable
data class DisplayOverrideState(
    /**
     * The user's ORIGINAL state. Note that "no override was set" is itself a
     * state worth recording (TZ 4.3 Tier 1): restoring must return the user to
     * *their* configuration, not to a factory number.
     */
    val originalDensityDpi: Int,
    val originalHadExplicitOverride: Boolean,
    val activeOverrideDensityDpi: Int? = null,
    val consentGrantedAtMillis: Long? = null,
    val verification: VerificationStatus = VerificationStatus.NOT_ATTEMPTED,
)

/** TZ 5.2 / A11: three distinct words, never conflated. */
enum class VerificationStatus {
    NOT_ATTEMPTED,

    /** We asked. We do not know whether it took effect. */
    REQUESTED,

    /** We asked and then measured the change ourselves. */
    OBSERVED,

    /** We asked and measurement showed it did NOT take effect. */
    FAILED,
}

/**
 * The seam between the shell's own Compose scaling and system-wide display
 * changes (TZ 4.1). Implementations must keep that separation absolute.
 */
interface SystemDisplayBridge {
    val tier: BridgeTier

    /** Human-readable status for the diagnostics screen (TZ 4.3 Tier 0). */
    fun statusLine(): String

    suspend fun applyDensity(targetDensityDpi: Int, state: DisplayOverrideState): ApplyResult

    suspend fun restore(state: DisplayOverrideState): ApplyResult
}

/**
 * Tier 0: the bridge that ships enabled.
 *
 * It refuses every write, and says so plainly. This is the honest default
 * required by TZ 4.3: "System-wide tablet adaptation unavailable".
 */
class NoPrivilegeDisplayBridge(
    private val widthPx: Int,
    private val hasWriteSecureSettings: Boolean,
) : SystemDisplayBridge {

    override val tier: BridgeTier = BridgeTier.NO_PRIVILEGE

    override fun statusLine(): String =
        "System-wide tablet adaptation unavailable (Tier 0, no privileged bridge). " +
            "WRITE_SECURE_SETTINGS=" + (if (hasWriteSecureSettings) "granted" else "not granted") + "."

    override suspend fun applyDensity(
        targetDensityDpi: Int,
        state: DisplayOverrideState,
    ): ApplyResult = ApplyResult.ManualProcedureRequired(
        applyCommands = adbApplyCommands(targetDensityDpi),
        restoreCommands = adbRestoreCommands(state),
        note = "An ordinary APK holds no shell privileges, so it cannot change the " +
            "system display density. WRITE_SETTINGS would not help either: density is " +
            "not a WRITE_SETTINGS-governed value. Run the commands below from a host " +
            "over ADB. The shell will then re-read the metrics and report whether the " +
            "change was actually OBSERVED.",
    )

    override suspend fun restore(state: DisplayOverrideState): ApplyResult =
        ApplyResult.ManualProcedureRequired(
            applyCommands = emptyList(),
            restoreCommands = adbRestoreCommands(state),
            note = "Restore is an operator action over ADB. See docs/DISPLAY_ROLLBACK.md.",
        )

    private fun adbApplyCommands(dpi: Int): List<String> = listOf(
        "# record the current state first",
        "adb shell wm density",
        "adb shell wm size",
        "# apply",
        "adb shell wm density $dpi",
    )

    /**
     * Restores the user's own prior state. If they had NO override, the correct
     * restore is `wm density reset`, not writing a number back (TZ 4.3).
     */
    private fun adbRestoreCommands(state: DisplayOverrideState): List<String> =
        if (state.originalHadExplicitOverride) {
            listOf("adb shell wm density ${state.originalDensityDpi}")
        } else {
            listOf(
                "# the user had no density override; reset rather than set a number",
                "adb shell wm density reset",
            )
        }

    companion object {
        fun create(context: Context, widthPx: Int): NoPrivilegeDisplayBridge {
            val granted = context.checkCallingOrSelfPermission(
                "android.permission.WRITE_SECURE_SETTINGS",
            ) == PackageManager.PERMISSION_GRANTED
            return NoPrivilegeDisplayBridge(widthPx, granted)
        }
    }
}

/**
 * Tier 2 placeholder. Deliberately unimplemented (TZ 4.3, 13).
 *
 * Kept so the call sites and the UI are already tier-agnostic, and so that no
 * one is tempted to smuggle hidden-API calls into the Tier 0 path.
 */
interface PrivilegedDisplayAdapter : SystemDisplayBridge {
    /** True only when a real privileged channel has been established. */
    fun isConnected(): Boolean
}
