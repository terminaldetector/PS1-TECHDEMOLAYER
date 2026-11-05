package com.metro.opticalshell

import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.panels.ObservedTask
import com.metro.opticalshell.panels.TaskObservation
import com.metro.opticalshell.panels.TaskSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins TZ §4.3 and §6.3: the five task notions stay distinct, and a heuristic
 * is never presented as a window list.
 */
class TaskObservationTest {

    @Test
    fun `only real system sources count as live state`() {
        assertTrue(TaskSource.TRUE_RUNNING_TASK.isLiveSystemState)
        assertTrue(TaskSource.REAL_SYSTEM_WINDOW.isLiveSystemState)
        assertFalse(TaskSource.PINNED.isLiveSystemState)
        assertFalse(TaskSource.SHELL_LAUNCHED.isLiveSystemState)
        assertFalse(
            "usage-stats recency is not a window list",
            TaskSource.OBSERVED_RECENCY.isLiveSystemState,
        )
    }

    @Test
    fun `recency explicitly says it is not a window list`() {
        assertTrue(
            TaskSource.OBSERVED_RECENCY.accuracy.contains("not a list of open windows"),
        )
    }

    @Test
    fun `an unprivileged shell can only claim shell-launched`() {
        val source = TaskObservation.bestAvailableSource(CapabilityGate.HeldPrivileges())
        assertEquals(TaskSource.SHELL_LAUNCHED, source)
        assertFalse(source.isLiveSystemState)
    }

    @Test
    fun `usage stats consent upgrades to recency but no further`() {
        val held = CapabilityGate.HeldPrivileges(hasUsageStatsAccess = true)
        val source = TaskObservation.bestAvailableSource(held)
        assertEquals(TaskSource.OBSERVED_RECENCY, source)
        assertFalse(source.isLiveSystemState)
    }

    @Test
    fun `only a shell bridge yields a true task list`() {
        val held = CapabilityGate.HeldPrivileges(shellBridgeConnected = true)
        assertEquals(TaskSource.TRUE_RUNNING_TASK, TaskObservation.bestAvailableSource(held))
    }

    @Test
    fun `recents defers to the system without a real task list`() {
        assertTrue(
            TaskObservation.recentsShouldDeferToSystem(CapabilityGate.HeldPrivileges()),
        )
        assertTrue(
            TaskObservation.recentsShouldDeferToSystem(
                CapabilityGate.HeldPrivileges(hasUsageStatsAccess = true),
            ),
        )
        assertFalse(
            TaskObservation.recentsShouldDeferToSystem(
                CapabilityGate.HeldPrivileges(shellBridgeConnected = true),
            ),
        )
    }

    @Test
    fun `every non-live source carries a disclaimer`() {
        TaskSource.entries.forEach { source ->
            if (source.isLiveSystemState) {
                assertNull(TaskObservation.disclaimerFor(source))
            } else {
                assertNotNull("${source.name} needs a disclaimer", TaskObservation.disclaimerFor(source))
            }
        }
    }

    @Test
    fun `merge prefers the highest fidelity source per package`() {
        val merged = TaskObservation.merge(
            pinned = listOf(ObservedTask("com.a", "A", TaskSource.PINNED)),
            shellLaunched = listOf(ObservedTask("com.a", "A", TaskSource.SHELL_LAUNCHED)),
            observed = listOf(ObservedTask("com.a", "A", TaskSource.TRUE_RUNNING_TASK)),
        )
        assertEquals(1, merged.size)
        assertEquals(TaskSource.TRUE_RUNNING_TASK, merged.first().source)
    }

    @Test
    fun `merge keeps distinct packages`() {
        val merged = TaskObservation.merge(
            pinned = listOf(ObservedTask("com.a", "A", TaskSource.PINNED)),
            shellLaunched = listOf(ObservedTask("com.b", "B", TaskSource.SHELL_LAUNCHED)),
            observed = emptyList(),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `required capability matches the tier model`() {
        assertNull(TaskSource.PINNED.requiredCapability)
        assertEquals(
            CapabilityGate.Ids.INFER_APP_RECENCY,
            TaskSource.OBSERVED_RECENCY.requiredCapability,
        )
        assertEquals(
            CapabilityGate.Ids.LIST_RUNNING_TASKS,
            TaskSource.TRUE_RUNNING_TASK.requiredCapability,
        )
    }
}
