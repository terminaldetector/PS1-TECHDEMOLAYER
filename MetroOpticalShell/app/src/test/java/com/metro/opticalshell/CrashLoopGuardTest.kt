package com.metro.opticalshell

import com.metro.opticalshell.core.CrashLoopGuard
import com.metro.opticalshell.core.CrashLoopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins TZ §12: auto-disable our own features, never touch the system. */
class CrashLoopGuardTest {

    @Test
    fun `one crash does not disable a feature`() {
        val s = CrashLoopGuard.recordCrash(
            CrashLoopState(), CrashLoopGuard.Features.OPTICAL_LAYER, "boom", 1,
        )
        assertFalse(CrashLoopGuard.isDisabled(s, CrashLoopGuard.Features.OPTICAL_LAYER))
    }

    @Test
    fun `the threshold disables it and records why`() {
        var s = CrashLoopState()
        repeat(CrashLoopGuard.THRESHOLD) {
            s = CrashLoopGuard.recordCrash(
                s, CrashLoopGuard.Features.WALLPAPER_MASK, "OOM decoding wallpaper", it.toLong(),
            )
        }
        assertTrue(CrashLoopGuard.isDisabled(s, CrashLoopGuard.Features.WALLPAPER_MASK))
        val explain = CrashLoopGuard.explain(s, CrashLoopGuard.Features.WALLPAPER_MASK)
        assertNotNull(explain)
        assertTrue(explain!!.contains("OOM decoding wallpaper"))
        assertTrue(explain.contains("turn it back on"))
    }

    @Test
    fun `a clean run clears the streak so old flukes do not accumulate`() {
        var s = CrashLoopGuard.recordCrash(
            CrashLoopState(), CrashLoopGuard.Features.OPTICAL_LAYER, "boom", 1,
        )
        s = CrashLoopGuard.recordCleanRun(s, CrashLoopGuard.Features.OPTICAL_LAYER)
        // One more crash should not now disable it.
        s = CrashLoopGuard.recordCrash(s, CrashLoopGuard.Features.OPTICAL_LAYER, "boom", 2)
        assertFalse(CrashLoopGuard.isDisabled(s, CrashLoopGuard.Features.OPTICAL_LAYER))
    }

    @Test
    fun `essential features are never auto-disabled`() {
        var s = CrashLoopState()
        repeat(10) { s = CrashLoopGuard.recordCrash(s, "tile_grid", "boom", it.toLong()) }
        assertFalse(CrashLoopGuard.isDisabled(s, "tile_grid"))
        assertTrue(CrashLoopGuard.disabledFeatures(s).isEmpty())
    }

    @Test
    fun `the user can re-enable a guarded feature`() {
        var s = CrashLoopState()
        repeat(CrashLoopGuard.THRESHOLD) {
            s = CrashLoopGuard.recordCrash(s, CrashLoopGuard.Features.OVERLAY_TASKBAR, "x", 1)
        }
        assertTrue(CrashLoopGuard.isDisabled(s, CrashLoopGuard.Features.OVERLAY_TASKBAR))
        s = CrashLoopGuard.reEnable(s, CrashLoopGuard.Features.OVERLAY_TASKBAR)
        assertFalse(CrashLoopGuard.isDisabled(s, CrashLoopGuard.Features.OVERLAY_TASKBAR))
        assertNull(CrashLoopGuard.explain(s, CrashLoopGuard.Features.OVERLAY_TASKBAR))
    }

    @Test
    fun `a clean run for an untracked feature is a no-op`() {
        val s = CrashLoopState()
        assertSame(s, CrashLoopGuard.recordCleanRun(s, "never_crashed"))
    }

    @Test
    fun `disabled features are listed`() {
        var s = CrashLoopState()
        repeat(CrashLoopGuard.THRESHOLD) {
            s = CrashLoopGuard.recordCrash(s, CrashLoopGuard.Features.OPTICAL_LAYER, "a", 1)
            s = CrashLoopGuard.recordCrash(s, CrashLoopGuard.Features.DISPLAY_PROFILE, "b", 1)
        }
        assertEquals(
            listOf(CrashLoopGuard.Features.DISPLAY_PROFILE, CrashLoopGuard.Features.OPTICAL_LAYER)
                .sorted(),
            CrashLoopGuard.disabledFeatures(s),
        )
    }
}
