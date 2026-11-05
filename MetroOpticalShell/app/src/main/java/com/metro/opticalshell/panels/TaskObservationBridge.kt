package com.metro.opticalshell.panels

import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.capability.CapabilityState

/**
 * The five distinct things TZ §4.3 forbids conflating:
 *
 *   pinned · recently launched · observed foreground · truly running task ·
 *   real system window
 *
 * Each carries the accuracy of its own source. The point of this type is that
 * the UI physically cannot show a heuristic as if it were a window list,
 * because the label travels with the data (TZ §6.3).
 */
enum class TaskSource {
    /** The user pinned it. We know this exactly. */
    PINNED,

    /** This shell launched it. We know we asked; not that it still runs. */
    SHELL_LAUNCHED,

    /** Inferred from UsageStats. Recency, not a window. Needs L1 consent. */
    OBSERVED_RECENCY,

    /** A real task from IActivityManager. Needs an L2 bridge. */
    TRUE_RUNNING_TASK,

    /** A real system window with a windowing mode. Needs L2 + reflection. */
    REAL_SYSTEM_WINDOW,
    ;

    /** What may honestly be claimed about an entry from this source. */
    val accuracy: String
        get() = when (this) {
            PINNED -> "Pinned by you."
            SHELL_LAUNCHED -> "Launched from this shell. It may since have been closed."
            OBSERVED_RECENCY ->
                "Inferred from usage statistics. This is recency, not a list of " +
                    "open windows, and it may be stale or incomplete."
            TRUE_RUNNING_TASK -> "A running task, read from the system."
            REAL_SYSTEM_WINDOW -> "A real system window."
        }

    /** True only where the source genuinely reflects live system state. */
    val isLiveSystemState: Boolean
        get() = this == TRUE_RUNNING_TASK || this == REAL_SYSTEM_WINDOW

    val requiredCapability: String?
        get() = when (this) {
            PINNED, SHELL_LAUNCHED -> null
            OBSERVED_RECENCY -> CapabilityGate.Ids.INFER_APP_RECENCY
            TRUE_RUNNING_TASK, REAL_SYSTEM_WINDOW -> CapabilityGate.Ids.LIST_RUNNING_TASKS
        }
}

/** One entry in the taskbar, inseparable from where it came from. */
data class ObservedTask(
    val packageName: String,
    val label: String,
    val source: TaskSource,
    val taskId: Int? = null,
    val windowingMode: Int? = null,
)

/**
 * Chooses the best available task source and refuses to overstate it
 * (TZ §4.3, §6.3).
 */
object TaskObservation {

    /**
     * The best source currently usable, highest fidelity first.
     *
     * Note what this cannot return without privileges: anything
     * [TaskSource.isLiveSystemState]. That is the honest ceiling of an
     * unprivileged shell, and it is why the OP01 taskbar showed only pinned and
     * shell-launched apps.
     */
    fun bestAvailableSource(held: CapabilityGate.HeldPrivileges): TaskSource {
        val running = CapabilityGate.stateFor(CapabilityGate.Ids.LIST_RUNNING_TASKS, held)
        if (running == CapabilityState.AVAILABLE) return TaskSource.TRUE_RUNNING_TASK

        val recency = CapabilityGate.stateFor(CapabilityGate.Ids.INFER_APP_RECENCY, held)
        if (recency == CapabilityState.AVAILABLE) return TaskSource.OBSERVED_RECENCY

        return TaskSource.SHELL_LAUNCHED
    }

    /**
     * The disclaimer the taskbar must display for a given source.
     *
     * Never null for a non-live source: TZ §6.3 requires the accuracy of the
     * data to be shown, not implied.
     */
    fun disclaimerFor(source: TaskSource): String? =
        if (source.isLiveSystemState) null else source.accuracy

    /**
     * Whether a "Recents" button should open the SYSTEM overview rather than
     * our own list (TZ §6.3 P0).
     *
     * Without a true task list we cannot draw a faithful Recents, so the only
     * honest button is one that hands over to the system's own.
     */
    fun recentsShouldDeferToSystem(held: CapabilityGate.HeldPrivileges): Boolean =
        !bestAvailableSource(held).isLiveSystemState

    fun merge(
        pinned: List<ObservedTask>,
        shellLaunched: List<ObservedTask>,
        observed: List<ObservedTask>,
    ): List<ObservedTask> {
        val seen = HashSet<String>()
        val out = ArrayList<ObservedTask>()
        // Highest-fidelity source wins for a given package.
        listOf(observed, shellLaunched, pinned).forEach { group ->
            group.sortedBy { it.source.ordinal }.forEach {
                if (seen.add(it.packageName)) out.add(it)
            }
        }
        return out.sortedBy { it.source.ordinal }
    }
}
