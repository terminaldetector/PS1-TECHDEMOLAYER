package com.metro.opticalshell.display

import com.metro.opticalshell.core.ShellOrientation

/**
 * Stabilises orientation so the shell does not thrash (TZ §5.6).
 *
 * Two distinct hazards it guards against:
 *
 *  1. **Sensor jitter.** A phone held near 45° can report alternating
 *     orientations many times a second. Switching presentation on each would
 *     make the shell unusable, so a change must persist for [settleMillis]
 *     before it counts.
 *  2. **Recursive configuration changes.** Applying a display profile itself
 *     triggers a configuration change, which could request another profile, and
 *     so on. [beginProfileApplication] suppresses input entirely until the
 *     application finishes, which is why TZ §5.6 forbids "recursive profile
 *     changes on Activity recreation".
 *
 * Pure and time-injected, so both hazards are unit-testable without a device.
 */
class OrientationDebouncer(
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
) {
    private var stable: ShellOrientation? = null
    private var candidate: ShellOrientation? = null
    private var candidateSinceMillis: Long = 0
    private var suppressUntilSettled = false

    /** The last orientation accepted as stable. */
    val stableOrientation: ShellOrientation? get() = stable

    val isApplyingProfile: Boolean get() = suppressUntilSettled

    /**
     * Feeds a raw reading. Returns the orientation to act on, or null when
     * nothing should change yet.
     */
    fun offer(reading: ShellOrientation, nowMillis: Long): ShellOrientation? {
        // Hazard 2: ignore everything while a profile is being applied.
        if (suppressUntilSettled) return null

        if (stable == null) {
            stable = reading
            candidate = null
            return reading
        }
        if (reading == stable) {
            candidate = null
            return null
        }
        if (reading != candidate) {
            candidate = reading
            candidateSinceMillis = nowMillis
            return null
        }
        // Hazard 1: the same new reading must persist long enough.
        return if (nowMillis - candidateSinceMillis >= settleMillis) {
            stable = reading
            candidate = null
            reading
        } else {
            null
        }
    }

    /** Call immediately before applying a display profile. */
    fun beginProfileApplication() {
        suppressUntilSettled = true
        candidate = null
    }

    /**
     * Call when the profile has been applied and the configuration has settled.
     * The next reading is then treated as a fresh baseline rather than a change,
     * so applying a profile cannot itself look like a rotation.
     */
    fun endProfileApplication(settledOrientation: ShellOrientation) {
        suppressUntilSettled = false
        stable = settledOrientation
        candidate = null
    }

    fun reset() {
        stable = null
        candidate = null
        suppressUntilSettled = false
    }

    companion object {
        const val DEFAULT_SETTLE_MILLIS = 450L
    }
}
