package com.metro.opticalshell.privileged

import com.metro.opticalshell.capability.PrivilegeTier

/**
 * The L2 bridge interface (TZ §10 OP02-F, §2 tier L2).
 *
 * **No implementation ships.** There is no Shizuku dependency in this build and
 * no reflection into hidden APIs anywhere in the shipped paths. This interface
 * exists so that the capability model, the UI and the landscape engine are
 * already tier-agnostic, and so nobody is tempted to smuggle a privileged call
 * into a Tier 0 code path to make a feature "work".
 *
 * What a real implementation would provide, and what it would cost
 * (all verified in docs/OP02_UPSTREAM_RESEARCH.md):
 *
 *  - a true running-task list, via `IActivityManager` through a Shizuku binder;
 *  - each task's windowing mode, by reflection on `RunningTaskInfo`;
 *  - `ActivityOptions.setLaunchWindowingMode`, a hidden API;
 *  - display density, via `wm density` or the hidden
 *    `IWindowManager.setForcedDisplayDensityForUser`.
 *
 * What it would still NOT provide, at any price: becoming the Recents or
 * gesture provider. That needs `STATUS_BAR_SERVICE` and `MONITOR_INPUT`, which
 * are platform-signature permissions. A shell UID is not a platform signature.
 */
interface PrivilegedShellBridge {

    val tier: PrivilegeTier get() = PrivilegeTier.L2_SHELL_BRIDGE

    /** True only when a real privileged channel is established and alive. */
    fun isConnected(): Boolean

    /** Human-readable status for the diagnostics screen. */
    fun statusLine(): String

    suspend fun runningTasks(max: Int): Result<List<PrivilegedTaskInfo>>

    suspend fun setWindowingMode(taskId: Int, mode: Int): Result<Unit>

    suspend fun setDisplayDensity(displayId: Int, densityDpi: Int): Result<Unit>

    suspend fun resetDisplayDensity(displayId: Int): Result<Unit>
}

data class PrivilegedTaskInfo(
    val taskId: Int,
    val packageName: String,
    val activityName: String?,
    val windowingMode: Int?,
    val isRunning: Boolean,
)

/**
 * The bridge that ships: permanently disconnected.
 *
 * Every call fails with a reason. This is what makes the absence of L2 a
 * runtime fact the UI can display, rather than a missing class that would make
 * call sites branch on null.
 */
object AbsentShellBridge : PrivilegedShellBridge {

    private const val REASON =
        "No privileged bridge is present in this build. A true task list, " +
            "windowing-mode control and density changes all require an L2 shell " +
            "bridge (Shizuku or ADB), which is deliberately not bundled."

    override fun isConnected(): Boolean = false

    override fun statusLine(): String = REASON

    override suspend fun runningTasks(max: Int): Result<List<PrivilegedTaskInfo>> =
        Result.failure(UnsupportedOperationException(REASON))

    override suspend fun setWindowingMode(taskId: Int, mode: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException(REASON))

    override suspend fun setDisplayDensity(displayId: Int, densityDpi: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException(REASON))

    override suspend fun resetDisplayDensity(displayId: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException(REASON))
}
