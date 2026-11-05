package com.metro.opticalshell

import com.metro.opticalshell.display.ApplyResult
import com.metro.opticalshell.display.BridgeTier
import com.metro.opticalshell.display.DisplayOverrideState
import com.metro.opticalshell.display.NoPrivilegeDisplayBridge
import com.metro.opticalshell.display.VerificationStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins TZ 4.3 Tier 0 and A10/A12: the shipped bridge refuses every write, and
 * rollback restores the USER's prior state rather than a factory number.
 */
class DisplayBridgeTest {

    private val bridge = NoPrivilegeDisplayBridge(widthPx = 1080, hasWriteSecureSettings = false)

    @Test
    fun `tier 0 is the shipped default`() {
        assertEquals(BridgeTier.NO_PRIVILEGE, bridge.tier)
    }

    @Test
    fun `status line states unavailability honestly`() {
        val line = bridge.statusLine()
        assertTrue(line.contains("System-wide tablet adaptation unavailable"))
        assertTrue(line.contains("Tier 0"))
    }

    @Test
    fun `applying density never reports success from tier 0`() = runTest {
        val result = bridge.applyDensity(
            targetDensityDpi = 288,
            state = DisplayOverrideState(originalDensityDpi = 440, originalHadExplicitOverride = false),
        )
        assertTrue(
            "tier 0 must not report Applied",
            result is ApplyResult.ManualProcedureRequired,
        )
        result as ApplyResult.ManualProcedureRequired
        assertTrue(result.applyCommands.any { it.contains("wm density 288") })
        // And it must say WRITE_SETTINGS is not the answer.
        assertTrue(result.note.contains("WRITE_SETTINGS"))
    }

    @Test
    fun `restore resets when the user had no override`() = runTest {
        val result = bridge.restore(
            DisplayOverrideState(originalDensityDpi = 440, originalHadExplicitOverride = false),
        )
        result as ApplyResult.ManualProcedureRequired
        assertTrue(
            "with no prior override the restore must reset, not set a number",
            result.restoreCommands.any { it.contains("wm density reset") },
        )
        assertTrue(result.restoreCommands.none { it.contains("wm density 440") })
    }

    @Test
    fun `restore puts back the users own value when they had one`() = runTest {
        val result = bridge.restore(
            DisplayOverrideState(originalDensityDpi = 400, originalHadExplicitOverride = true),
        )
        result as ApplyResult.ManualProcedureRequired
        assertTrue(result.restoreCommands.any { it.contains("wm density 400") })
        assertTrue(result.restoreCommands.none { it.contains("reset") })
    }

    @Test
    fun `verification starts as not attempted`() {
        val state = DisplayOverrideState(originalDensityDpi = 440, originalHadExplicitOverride = false)
        assertEquals(VerificationStatus.NOT_ATTEMPTED, state.verification)
        assertEquals(null, state.activeOverrideDensityDpi)
        assertEquals(null, state.consentGrantedAtMillis)
    }
}
