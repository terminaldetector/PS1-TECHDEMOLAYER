package com.metro.opticalshell

import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.capability.CapabilityState
import com.metro.opticalshell.capability.RequiredGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the defects TZ v4 §1 identified (FIX-01…FIX-07).
 *
 * Each test fails against the previous implementation, which is the point: they
 * pin the fix rather than restate the intent.
 */
class FixVerificationTest {

    // ---- FIX-02: independent per-permission gating -----------------------

    @Test
    fun `FIX-02 one L1 grant does not unlock a different L1 capability`() {
        // The bug: a shared "highestTier" meant notification access made the
        // Home role, the overlay and usage stats all read AVAILABLE.
        val onlyNotifications = CapabilityGate.HeldPrivileges(hasNotificationAccess = true)

        assertEquals(
            CapabilityState.AVAILABLE,
            CapabilityGate.stateFor(CapabilityGate.Ids.READ_NOTIFICATIONS, onlyNotifications),
        )
        // ...and nothing else of the same tier comes with it.
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.BE_HOME, onlyNotifications),
        )
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.DRAW_OVERLAY, onlyNotifications),
        )
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.OPEN_SYSTEM_PANELS, onlyNotifications),
        )
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.INFER_APP_RECENCY, onlyNotifications),
        )
    }

    @Test
    fun `FIX-02 each L1 grant unlocks exactly its own capability`() {
        val cases = mapOf(
            CapabilityGate.Ids.BE_HOME to CapabilityGate.HeldPrivileges(isHome = true),
            CapabilityGate.Ids.DRAW_OVERLAY to
                CapabilityGate.HeldPrivileges(hasOverlayPermission = true),
            CapabilityGate.Ids.OPEN_SYSTEM_PANELS to
                CapabilityGate.HeldPrivileges(hasAccessibilityService = true),
            CapabilityGate.Ids.INFER_APP_RECENCY to
                CapabilityGate.HeldPrivileges(hasUsageStatsAccess = true),
        )
        cases.forEach { (capability, held) ->
            assertEquals(
                capability,
                CapabilityState.AVAILABLE,
                CapabilityGate.stateFor(capability, held),
            )
            // And every OTHER case's capability stays locked under this grant.
            cases.keys.filter { it != capability }.forEach { other ->
                assertEquals(
                    "$other must not be unlocked by the grant for $capability",
                    CapabilityState.PRIVILEGE_REQUIRED,
                    CapabilityGate.stateFor(other, held),
                )
            }
        }
    }

    @Test
    fun `FIX-02 usage stats does not grant a true task list`() {
        // The specific conflation TZ v4 6.1 calls out.
        val held = CapabilityGate.HeldPrivileges(hasUsageStatsAccess = true)
        assertEquals(
            CapabilityState.AVAILABLE,
            CapabilityGate.stateFor(CapabilityGate.Ids.INFER_APP_RECENCY, held),
        )
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.LIST_RUNNING_TASKS, held),
        )
    }

    @Test
    fun `FIX-02 notification access does not grant resizeTask`() {
        // Named explicitly by TZ v4 8: "notifications and overlay in no way
        // prove the ability to resizeTask".
        val held = CapabilityGate.HeldPrivileges(
            hasNotificationAccess = true,
            hasOverlayPermission = true,
        )
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.RESIZE_TASK, held),
        )
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.SET_WINDOWING_MODE, held),
        )
    }

    @Test
    fun `FIX-02 every capability names exactly one governing grant`() {
        val report = CapabilityGate.report(CapabilityGate.HeldPrivileges())
        report.forEach {
            assertTrue(
                "${it.id} has no governing grant",
                it.requiredGrant.isNotBlank(),
            )
        }
        // The L4 ones must be the platform-signature grant, which is never held.
        assertEquals(
            RequiredGrant.PLATFORM_SIGNATURE,
            CapabilityGate.grantOf(CapabilityGate.Ids.BE_RECENTS_PROVIDER),
        )
        assertEquals(
            RequiredGrant.PLATFORM_SIGNATURE,
            CapabilityGate.grantOf(CapabilityGate.Ids.REPLACE_STATUS_BAR),
        )
    }

    @Test
    fun `FIX-02 root implies shell reach but not the reverse`() {
        val shell = CapabilityGate.HeldPrivileges(shellBridgeConnected = true)
        val root = CapabilityGate.HeldPrivileges(rootAvailable = true)

        // Root can do what the shell bridge can.
        assertEquals(
            CapabilityState.AVAILABLE,
            CapabilityGate.stateFor(CapabilityGate.Ids.SET_WINDOWING_MODE, root),
        )
        // The shell bridge cannot do what needs root.
        assertEquals(
            CapabilityState.PRIVILEGE_REQUIRED,
            CapabilityGate.stateFor(CapabilityGate.Ids.RESIZE_TASK, shell),
        )
    }

    @Test
    fun `FIX-02 grants map is complete for every declared grant`() {
        val grants = CapabilityGate.grantsOf(CapabilityGate.HeldPrivileges())
        RequiredGrant.entries.forEach {
            assertTrue("grant ${it.name} is not represented", grants.containsKey(it))
        }
        // Nothing is held with no permissions, except the trivial NONE.
        assertEquals(true, grants[RequiredGrant.NONE])
        assertNotEquals(true, grants[RequiredGrant.PLATFORM_SIGNATURE])
    }
}
