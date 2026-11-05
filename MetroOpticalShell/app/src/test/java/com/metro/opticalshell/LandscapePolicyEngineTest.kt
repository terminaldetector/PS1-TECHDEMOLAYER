package com.metro.opticalshell

import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.compat.AppCompatClass
import com.metro.opticalshell.compat.AppCompatibilityProfile
import com.metro.opticalshell.compat.LandscapeMode
import com.metro.opticalshell.compat.LandscapePolicyEngine
import com.metro.opticalshell.compat.LayoutObservation
import com.metro.opticalshell.compat.RotationPolicy
import com.metro.opticalshell.compat.WindowIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins TZ §4 and §5: two honest landscape modes, no forced rotation. */
class LandscapePolicyEngineTest {

    private val noPrivilege = CapabilityGate.HeldPrivileges()
    private val withBridge = CapabilityGate.HeldPrivileges(shellBridgeConnected = true)

    private fun profile(
        pkg: String = "com.example",
        cls: AppCompatClass? = null,
        windowIntent: WindowIntent = WindowIntent.AUTO,
    ) = AppCompatibilityProfile(
        packageName = pkg,
        observedClass = cls,
        preferredWindowMode = windowIntent,
    )

    @Test
    fun `windowed request falls back to fullscreen without a bridge`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(),
            requestedMode = LandscapeMode.MULTI_WINDOW_DESKTOP,
            windowWidthDp = 900,
            held = noPrivilege,
            freeformSupported = true,
        )
        // Even with the device reporting freeform, the hidden API needs L2.
        assertEquals(LandscapeMode.FOCUSED_FULLSCREEN, policy.mode)
        assertFalse(policy.requestBounds)
        assertTrue(policy.rationale.contains("hidden API"))
        assertTrue(policy.honestOutcome.contains("desktop stays usable"))
    }

    @Test
    fun `windowed works with a bridge and real support`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(),
            requestedMode = LandscapeMode.MULTI_WINDOW_DESKTOP,
            windowWidthDp = 900,
            held = withBridge,
            freeformSupported = true,
        )
        assertEquals(LandscapeMode.MULTI_WINDOW_DESKTOP, policy.mode)
        assertTrue(policy.requestBounds)
        // Still refuses to claim a window appeared.
        assertTrue(policy.honestOutcome.contains("Confirm visually"))
    }

    @Test
    fun `a bridge does not help when the device has no freeform`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(),
            requestedMode = LandscapeMode.MULTI_WINDOW_DESKTOP,
            windowWidthDp = 900,
            held = withBridge,
            freeformSupported = false,
        )
        assertEquals(LandscapeMode.FOCUSED_FULLSCREEN, policy.mode)
        assertTrue(policy.rationale.contains("no freeform support"))
    }

    @Test
    fun `portrait-locked apps are never rotated`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(cls = AppCompatClass.C_PORTRAIT_LOCKED),
            requestedMode = LandscapeMode.MULTI_WINDOW_DESKTOP,
            windowWidthDp = 1200,
            held = withBridge,
            freeformSupported = true,
        )
        assertTrue("must respect the app's orientation", policy.respectAppOrientation)
        assertTrue(policy.acceptLetterbox)
        assertEquals(LandscapeMode.FOCUSED_FULLSCREEN, policy.mode)
        assertTrue(policy.rationale.contains("no API to change"))
        assertTrue(policy.honestOutcome.contains("No false landscape"))
    }

    @Test
    fun `protected pipelines are left completely alone`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(cls = AppCompatClass.E_PROTECTED_PIPELINE),
            requestedMode = LandscapeMode.MULTI_WINDOW_DESKTOP,
            windowWidthDp = 1200,
            held = withBridge,
            freeformSupported = true,
        )
        assertEquals(LandscapeMode.FOCUSED_FULLSCREEN, policy.mode)
        assertFalse(policy.requestBounds)
        assertFalse(policy.acceptLetterbox)
        assertTrue(policy.respectAppOrientation)
    }

    @Test
    fun `unstable apps get a stable configuration not a restart loop`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(cls = AppCompatClass.D_UNSTABLE_ON_ROTATE),
            requestedMode = LandscapeMode.FOCUSED_FULLSCREEN,
            windowWidthDp = 900,
            held = noPrivilege,
            freeformSupported = false,
        )
        assertTrue(policy.respectAppOrientation)
        assertTrue(policy.honestOutcome.contains("compromise, not a fix"))
    }

    @Test
    fun `an unstable app may be allowed to be recreated if the user insists`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(cls = AppCompatClass.D_UNSTABLE_ON_ROTATE)
                .copy(rotationPolicy = RotationPolicy.ALLOW_RECREATION),
            requestedMode = LandscapeMode.FOCUSED_FULLSCREEN,
            windowWidthDp = 900,
            held = noPrivilege,
            freeformSupported = false,
        )
        assertFalse(policy.respectAppOrientation)
    }

    @Test
    fun `compact width is reported as a phone layout`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(),
            requestedMode = LandscapeMode.FOCUSED_FULLSCREEN,
            windowWidthDp = 540,
            held = noPrivilege,
            freeformSupported = false,
        )
        assertTrue(policy.honestOutcome.contains("COMPACT"))
        assertTrue(policy.honestOutcome.contains("phone layouts"))
    }

    @Test
    fun `width alone never promises a tablet layout for an unclassified app`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(),
            requestedMode = LandscapeMode.FOCUSED_FULLSCREEN,
            windowWidthDp = 900,
            held = noPrivilege,
            freeformSupported = false,
        )
        assertTrue(policy.honestOutcome.contains("may show"))
        assertTrue(policy.honestOutcome.contains("unverified"))
        assertTrue(policy.rationale.contains("No observation recorded"))
    }

    @Test
    fun `a resizable phone-UI app is described as larger not different`() {
        val policy = LandscapePolicyEngine.decide(
            profile = profile(cls = AppCompatClass.B_RESIZABLE_PHONE_UI),
            requestedMode = LandscapeMode.FOCUSED_FULLSCREEN,
            windowWidthDp = 900,
            held = noPrivilege,
            freeformSupported = false,
        )
        assertTrue(policy.honestOutcome.contains("larger, not different"))
    }

    // ---- classification -------------------------------------------------

    @Test
    fun `no observations means no class`() {
        assertNull(LandscapePolicyEngine.classify(emptyList()))
    }

    @Test
    fun `width without a determination stays unknown`() {
        val obs = LayoutObservation(
            atMillis = 1,
            windowWidthDp = 800,
            windowHeightDp = 400,
            densityDpi = 288,
            landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
            layoutChanged = null,
        )
        assertNull("unknown must not be upgraded to a class", LandscapePolicyEngine.classify(listOf(obs)))
    }

    @Test
    fun `a layout change without evidence does not earn class A`() {
        val obs = LayoutObservation(
            atMillis = 1,
            windowWidthDp = 800,
            windowHeightDp = 400,
            densityDpi = 288,
            landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
            layoutChanged = true,
            evidence = null,
        )
        assertNull(LandscapePolicyEngine.classify(listOf(obs)))
    }

    @Test
    fun `a layout change with evidence earns class A`() {
        val obs = LayoutObservation(
            atMillis = 1,
            windowWidthDp = 800,
            windowHeightDp = 400,
            densityDpi = 288,
            landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
            layoutChanged = true,
            evidence = "screenshots/files-800dp.png",
        )
        assertEquals(AppCompatClass.A_ADAPTIVE, LandscapePolicyEngine.classify(listOf(obs)))
    }

    @Test
    fun `observed no-change at width with evidence earns class B`() {
        val obs = LayoutObservation(
            atMillis = 1,
            windowWidthDp = 800,
            windowHeightDp = 400,
            densityDpi = 288,
            landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
            layoutChanged = false,
            evidence = "screenshots/x.png",
        )
        assertEquals(AppCompatClass.B_RESIZABLE_PHONE_UI, LandscapePolicyEngine.classify(listOf(obs)))
    }

    @Test
    fun `recreation on rotate dominates classification`() {
        val obs = listOf(
            LayoutObservation(
                atMillis = 1, windowWidthDp = 800, windowHeightDp = 400, densityDpi = 288,
                landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
                layoutChanged = true, evidence = "x.png",
            ),
            LayoutObservation(
                atMillis = 2, windowWidthDp = 800, windowHeightDp = 400, densityDpi = 288,
                landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
                recreatedOnRotate = true,
            ),
        )
        assertEquals(AppCompatClass.D_UNSTABLE_ON_ROTATE, LandscapePolicyEngine.classify(obs))
    }

    @Test
    fun `verified tablet layout requires evidence`() {
        val withoutEvidence = profile().copy(
            observations = listOf(
                LayoutObservation(
                    atMillis = 1, windowWidthDp = 800, windowHeightDp = 400,
                    densityDpi = 288, landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
                    layoutChanged = true,
                ),
            ),
        )
        assertFalse(withoutEvidence.hasVerifiedTabletLayout)

        val withEvidence = profile().copy(
            observations = listOf(
                LayoutObservation(
                    atMillis = 1, windowWidthDp = 800, windowHeightDp = 400,
                    densityDpi = 288, landscapeMode = LandscapeMode.FOCUSED_FULLSCREEN,
                    layoutChanged = true, evidence = "shot.png",
                ),
            ),
        )
        assertTrue(withEvidence.hasVerifiedTabletLayout)
    }

    @Test
    fun `profile clamps readability scale and width`() {
        val c = profile().copy(readabilityScale = 9f, preferredLogicalWidthDp = 99999).clamped()
        assertEquals(2.0f, c.readabilityScale, 0.001f)
        assertEquals(2048, c.preferredLogicalWidthDp)
    }
}
