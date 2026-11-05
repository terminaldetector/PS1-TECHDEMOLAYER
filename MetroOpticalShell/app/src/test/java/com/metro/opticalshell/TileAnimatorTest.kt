package com.metro.opticalshell

import com.metro.opticalshell.metro.AnimationConditions
import com.metro.opticalshell.metro.TileAnimationPolicy
import com.metro.opticalshell.metro.TileAnimationStyle
import com.metro.opticalshell.metro.TileAnimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins TZ §3.3 and §12: restrained animation, paused whenever it should be. */
class TileAnimatorTest {

    private val allOk = AnimationConditions(
        tileVisible = true,
        screenOn = true,
        shellInForeground = true,
        powerSaveMode = false,
        reducedMotionRequested = false,
        editMode = false,
    )
    private val flip = TileAnimationPolicy(style = TileAnimationStyle.FLIP)

    @Test
    fun `no style means no animation`() {
        assertFalse(TileAnimator.shouldAnimate(TileAnimationPolicy(), allOk))
    }

    @Test
    fun `animates when everything is favourable`() {
        assertTrue(TileAnimator.shouldAnimate(flip, allOk))
    }

    @Test
    fun `every condition is a veto`() {
        assertFalse(TileAnimator.shouldAnimate(flip, allOk.copy(tileVisible = false)))
        assertFalse(TileAnimator.shouldAnimate(flip, allOk.copy(screenOn = false)))
        assertFalse(TileAnimator.shouldAnimate(flip, allOk.copy(shellInForeground = false)))
        assertFalse(TileAnimator.shouldAnimate(flip, allOk.copy(powerSaveMode = true)))
        assertFalse(TileAnimator.shouldAnimate(flip, allOk.copy(reducedMotionRequested = true)))
        // Dragging a flipping tile would be unusable.
        assertFalse(TileAnimator.shouldAnimate(flip, allOk.copy(editMode = true)))
    }

    @Test
    fun `power saver and reduced motion can be opted out of`() {
        val ignoring = flip.copy(respectPowerSaver = false, respectReducedMotion = false)
        assertTrue(TileAnimator.shouldAnimate(ignoring, allOk.copy(powerSaveMode = true)))
        assertTrue(TileAnimator.shouldAnimate(ignoring, allOk.copy(reducedMotionRequested = true)))
    }

    @Test
    fun `interval is clamped to a sane range`() {
        assertEquals(TileAnimationPolicy.MIN_INTERVAL, flip.copy(intervalSeconds = 0).clamped().intervalSeconds)
        assertEquals(TileAnimationPolicy.MAX_INTERVAL, flip.copy(intervalSeconds = 9999).clamped().intervalSeconds)
    }

    @Test
    fun `flip and slide use two faces, cycle uses all`() {
        assertEquals(1, TileAnimator.faceCount(TileAnimationStyle.NONE, 5))
        assertEquals(2, TileAnimator.faceCount(TileAnimationStyle.FLIP, 5))
        assertEquals(2, TileAnimator.faceCount(TileAnimationStyle.SLIDE, 5))
        assertEquals(5, TileAnimator.faceCount(TileAnimationStyle.CYCLE, 5))
        // Never more faces than exist.
        assertEquals(1, TileAnimator.faceCount(TileAnimationStyle.FLIP, 1))
    }

    @Test
    fun `face index advances deterministically with time`() {
        val policy = TileAnimationPolicy(style = TileAnimationStyle.FLIP, intervalSeconds = 5)
        assertEquals(0, TileAnimator.faceAt(policy, 2, 0))
        assertEquals(0, TileAnimator.faceAt(policy, 2, 4_999))
        assertEquals(1, TileAnimator.faceAt(policy, 2, 5_000))
        assertEquals(0, TileAnimator.faceAt(policy, 2, 10_000))
    }

    @Test
    fun `a single face never advances`() {
        val policy = TileAnimationPolicy(style = TileAnimationStyle.CYCLE)
        assertEquals(0, TileAnimator.faceAt(policy, 1, 999_999))
    }

    @Test
    fun `stagger is stable across restarts and within the period`() {
        val policy = TileAnimationPolicy(style = TileAnimationStyle.FLIP, intervalSeconds = 6)
        val a = TileAnimator.staggerMillis("tile.one", policy)
        val b = TileAnimator.staggerMillis("tile.one", policy)
        assertEquals("stagger must be stable, not random", a, b)
        assertTrue(a in 0 until 6_000)
        // Different tiles generally differ, so a screen does not flip in unison.
        val other = TileAnimator.staggerMillis("tile.two", policy)
        assertTrue(a != other || "tile.one".hashCode() == "tile.two".hashCode())
    }
}
