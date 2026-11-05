package com.metro.opticalshell

import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.capability.CapabilityState
import com.metro.opticalshell.capability.PrivilegeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins TZ §2: no layer pretends to be another, and the gate cannot be talked
 * into saying a capability is available when it is not.
 */
class CapabilityGateTest {

    private val nothing = CapabilityGate.HeldPrivileges()
    private val shell = CapabilityGate.HeldPrivileges(shellBridgeConnected = true)
    private val root = CapabilityGate.HeldPrivileges(rootAvailable = true)
    private val granted = CapabilityGate.HeldPrivileges(isHome = true, hasNotificationAccess = true)

    @Test
    fun `L0 capabilities work with no privileges at all`() {
        listOf(
            CapabilityGate.Ids.LIST_APPS,
            CapabilityGate.Ids.LAUNCH_APP,
            CapabilityGate.Ids.DETECT_FREEFORM,
            CapabilityGate.Ids.REQUEST_LAUNCH_BOUNDS,
        ).forEach {
            assertEquals(it, CapabilityState.AVAILABLE, CapabilityGate.stateFor(it, nothing))
        }
    }

    @Test
    fun `platform-tier capabilities are impossible no matter what is granted`() {
        // The central honesty rule: nothing a user grants lifts an L4.
        listOf(nothing, granted, shell, root).forEach { held ->
            assertEquals(
                CapabilityState.IMPOSSIBLE_AT_APP_TIER,
                CapabilityGate.stateFor(CapabilityGate.Ids.BE_RECENTS_PROVIDER, held),
            )
            assertEquals(
                CapabilityState.IMPOSSIBLE_AT_APP_TIER,
                CapabilityGate.stateFor(CapabilityGate.Ids.REPLACE_STATUS_BAR, held),
            )
        }
    }

    @Test
    fun `even root cannot make us the recents provider`() {
        // Deliberately separate from the loop above, because this is the claim
        // people most want to believe.
        assertEquals(
            CapabilityState.IMPOSSIBLE_AT_APP_TIER,
            CapabilityGate.stateFor(CapabilityGate.Ids.BE_RECENTS_PROVIDER, root, true),
        )
    }

    @Test
    fun `a platform claiming support cannot raise a capability above its tier`() {
        // platformSupports = true must not promote an L2 capability for an
        // unprivileged app.
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.SET_WINDOWING_MODE, nothing, true),
        )
    }

    @Test
    fun `windowing mode needs the shell bridge`() {
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.SET_WINDOWING_MODE, nothing),
        )
        assertEquals(
            CapabilityState.AVAILABLE,
            CapabilityGate.stateFor(CapabilityGate.Ids.SET_WINDOWING_MODE, shell),
        )
    }

    @Test
    fun `task resize needs root and the bridge is not enough`() {
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.RESIZE_TASK, shell),
        )
        assertEquals(
            CapabilityState.AVAILABLE,
            CapabilityGate.stateFor(CapabilityGate.Ids.RESIZE_TASK, root),
        )
    }

    @Test
    fun `the platform can still veto downwards`() {
        assertEquals(
            CapabilityState.UNAVAILABLE,
            CapabilityGate.stateFor(CapabilityGate.Ids.DETECT_FREEFORM, nothing, false),
        )
    }

    @Test
    fun `density change is never available unprivileged`() {
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.SET_SYSTEM_DENSITY, nothing),
        )
        assertEquals(
            PrivilegeTier.L2_SHELL_BRIDGE,
            CapabilityGate.tierOf(CapabilityGate.Ids.SET_SYSTEM_DENSITY),
        )
    }

    @Test
    fun `running task list is L2 and recency inference is only L1`() {
        assertEquals(
            PrivilegeTier.L2_SHELL_BRIDGE,
            CapabilityGate.tierOf(CapabilityGate.Ids.LIST_RUNNING_TASKS),
        )
        assertEquals(
            PrivilegeTier.L1_USER_GRANTED,
            CapabilityGate.tierOf(CapabilityGate.Ids.INFER_APP_RECENCY),
        )
        // And the detail must say it is not a window list.
        assertTrue(
            CapabilityGate.detailOf(CapabilityGate.Ids.INFER_APP_RECENCY)
                .contains("NOT a list of live windows"),
        )
    }

    @Test
    fun `highest held tier is computed correctly`() {
        assertEquals(PrivilegeTier.L0_APP, nothing.highestTier)
        assertEquals(PrivilegeTier.L1_USER_GRANTED, granted.highestTier)
        assertEquals(PrivilegeTier.L2_SHELL_BRIDGE, shell.highestTier)
        assertEquals(PrivilegeTier.L3_ROOT, root.highestTier)
    }

    @Test
    fun `every negative claim carries a reason`() {
        val report = CapabilityGate.report(nothing)
        report.filter { it.state != CapabilityState.AVAILABLE.name }.forEach {
            assertTrue("${it.id} has no detail", it.detail.isNotBlank())
        }
    }

    @Test
    fun `tier claims above L1 cite a primary source`() {
        // A claim that something needs root or a bridge must be backed by
        // evidence, not by assertion.
        val report = CapabilityGate.report(nothing)
        report.filter {
            it.requiredTier in setOf(
                PrivilegeTier.L2_SHELL_BRIDGE.name,
                PrivilegeTier.L3_ROOT.name,
            )
        }.forEach {
            assertNotNull("${it.id} cites no evidence", it.evidence)
        }
    }

    @Test
    fun `the impossible list names exactly the platform-tier capabilities`() {
        val impossible = CapabilityGate.impossibleCapabilities()
        assertTrue(impossible.contains(CapabilityGate.Ids.BE_RECENTS_PROVIDER))
        assertTrue(impossible.contains(CapabilityGate.Ids.REPLACE_STATUS_BAR))
        impossible.forEach {
            assertEquals(PrivilegeTier.L4_PLATFORM, CapabilityGate.tierOf(it))
        }
    }

    @Test
    fun `an unknown capability is unknown rather than available`() {
        assertEquals(
            CapabilityState.UNKNOWN,
            CapabilityGate.stateFor("no_such_capability", root),
        )
    }

    @Test
    fun `report covers every declared capability`() {
        val report = CapabilityGate.report(nothing)
        assertEquals(15, report.size)
        assertFalse(report.any { it.id.isBlank() })
    }
}
