package com.metro.opticalshell.metro

import kotlinx.serialization.Serializable

/** Live tile animation styles (TZ §3.3). */
enum class TileAnimationStyle {
    /** No animation. The default and the only one for a plain launch tile. */
    NONE,

    /** WP's vertical flip between two faces. */
    FLIP,

    /** Content slides up, the next face replacing it. */
    SLIDE,

    /** Cycles through several faces. */
    CYCLE,
}

/**
 * When animations are allowed to run (TZ §3.3: "restrained animations that
 * pause when the tile is not visible or the screen is off, and per the power
 * saving setting").
 */
@Serializable
data class TileAnimationPolicy(
    val style: TileAnimationStyle = TileAnimationStyle.NONE,
    /** Seconds between faces. Floor enforced below. */
    val intervalSeconds: Int = 6,
    val respectPowerSaver: Boolean = true,
    val respectReducedMotion: Boolean = true,
) {
    fun clamped() = copy(intervalSeconds = intervalSeconds.coerceIn(MIN_INTERVAL, MAX_INTERVAL))

    companion object {
        /** Faster than this is a distraction and a battery cost, not a feature. */
        const val MIN_INTERVAL = 3
        const val MAX_INTERVAL = 60
    }
}

/** Everything that can stop a tile animating. */
data class AnimationConditions(
    val tileVisible: Boolean,
    val screenOn: Boolean,
    val shellInForeground: Boolean,
    val powerSaveMode: Boolean,
    val reducedMotionRequested: Boolean,
    val editMode: Boolean,
)

object TileAnimator {

    /**
     * Whether a tile may animate right now (TZ §3.3, §12).
     *
     * Every condition is a veto. In particular the shell must not animate while
     * it is not in the foreground: TZ §12 forbids a permanently running
     * background service for tiles, so there is nothing to tick then anyway, and
     * an animation that "resumes mid-cycle" would be lying about freshness.
     */
    fun shouldAnimate(policy: TileAnimationPolicy, conditions: AnimationConditions): Boolean {
        if (policy.style == TileAnimationStyle.NONE) return false
        if (!conditions.tileVisible) return false
        if (!conditions.screenOn) return false
        if (!conditions.shellInForeground) return false
        // Dragging a tile that is also flipping is unusable.
        if (conditions.editMode) return false
        if (policy.respectPowerSaver && conditions.powerSaveMode) return false
        if (policy.respectReducedMotion && conditions.reducedMotionRequested) return false
        return true
    }

    /** How many faces a style cycles through. */
    fun faceCount(style: TileAnimationStyle, availableFaces: Int): Int = when (style) {
        TileAnimationStyle.NONE -> 1
        TileAnimationStyle.FLIP, TileAnimationStyle.SLIDE -> minOf(2, availableFaces)
        TileAnimationStyle.CYCLE -> availableFaces.coerceAtLeast(1)
    }

    /** The face index at a given elapsed time. Deterministic, so it is testable. */
    fun faceAt(
        policy: TileAnimationPolicy,
        availableFaces: Int,
        elapsedMillis: Long,
    ): Int {
        val faces = faceCount(policy.style, availableFaces)
        if (faces <= 1) return 0
        val period = policy.clamped().intervalSeconds * 1000L
        return ((elapsedMillis / period) % faces).toInt()
    }

    /**
     * Stagger, so a screen of tiles does not flip in unison.
     *
     * Derived from the tile id so it is stable across restarts — a random
     * offset would make the start screen look different every launch.
     */
    fun staggerMillis(tileId: String, policy: TileAnimationPolicy): Long {
        val period = policy.clamped().intervalSeconds * 1000L
        val hash = tileId.hashCode().toLong() and 0x7FFFFFFF
        return hash % period
    }
}
