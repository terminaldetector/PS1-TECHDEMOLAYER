package com.metro.opticalshell.display

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The isolated component that MAY change the system display configuration
 * (TZ 4.1, 4.3, OP01-D.3).
 *
 * Hard boundary, and the reason this class exists at all: nothing in here
 * resizes a tile or a font. Shell-side scale lives in ShellScaleController.
 * System-side density lives here. TZ 4.1 requires them never to be the same
 * knob, because they move in opposite directions: more dp of width means
 * physically smaller UI.
 *
 * Safety rules encoded below:
 *  - a baseline is recorded before anything is attempted;
 *  - consent is per-attempt and must be passed in explicitly, never cached
 *    (TZ 15.7);
 *  - success is only ever reported after a verifying re-read;
 *  - the shipped Tier 0 bridge refuses every write.
 */
class DisplayAdaptationController(
    private val bridge: SystemDisplayBridge,
    private val repository: DisplayExperimentRepository,
    /** Re-reads live metrics so a change can be verified rather than assumed. */
    private val measure: () -> Pair<Int, Int>,
) {

    private val _lastResult = MutableStateFlow<ApplyResult?>(null)
    val lastResult: StateFlow<ApplyResult?> = _lastResult.asStateFlow()

    val tier: BridgeTier get() = bridge.tier

    fun statusLine(): String = bridge.statusLine()

    /**
     * Pre-flight only. Pure arithmetic, changes nothing (OP01-D.2).
     */
    fun assess(readout: DisplayReadout, targetWidthDp: Int): TabletFeasibility =
        DisplayMath.assess(
            widthPx = readout.maxWidthPx,
            heightPx = readout.maxHeightPx,
            originalDensityDpi = readout.densityDpi,
            targetWidthDp = targetWidthDp,
        )

    fun candidateDensities(readout: DisplayReadout): List<Int> =
        DisplayMath.candidateDensities(readout.densityDpi)

    /**
     * Attempt a density change.
     *
     * @param explicitConsent must be true, and must come from a confirmation
     *   shown immediately before this call (TZ 15.7). A default is deliberately
     *   not provided so a caller cannot forget it.
     */
    suspend fun applyDensity(
        targetDensityDpi: Int,
        readout: DisplayReadout,
        explicitConsent: Boolean,
    ): ApplyResult {
        if (!explicitConsent) {
            val refused = ApplyResult.Refused(
                BridgeUnavailableReason(
                    code = "NO_CONSENT",
                    detail = "A display density change requires an explicit confirmation " +
                        "immediately before it is attempted. Nothing was changed.",
                ),
            )
            _lastResult.value = refused
            return refused
        }

        val guard = (readout.densityDpi * DisplayMath.MIN_SAFE_DENSITY_FRACTION).toInt()
        if (targetDensityDpi < guard) {
            val refused = ApplyResult.Refused(
                BridgeUnavailableReason(
                    code = "BELOW_READABILITY_GUARD",
                    detail = "${targetDensityDpi}dpi is below this device's guard of " +
                        "${guard}dpi (${(DisplayMath.MIN_SAFE_DENSITY_FRACTION * 100).toInt()}% " +
                        "of the stock ${readout.densityDpi}dpi). Raise the target or lower the guard " +
                        "deliberately.",
                ),
            )
            _lastResult.value = refused
            return refused
        }

        // Baseline first: if anything goes wrong after this line, the record of
        // the user's own configuration is already on disk.
        val baseline = repository.recordBaselineOnce(
            currentDensityDpi = readout.densityDpi,
            hadExplicitOverride = false,
        )

        val result = bridge.applyDensity(targetDensityDpi, baseline)

        // Verify rather than assume (TZ 5.2 OBSERVED vs REQUESTED).
        val verified = when (result) {
            is ApplyResult.Applied -> {
                val (widthPx, densityDpi) = measure()
                if (densityDpi == targetDensityDpi) {
                    repository.save(
                        baseline.copy(
                            activeOverrideDensityDpi = targetDensityDpi,
                            consentGrantedAtMillis = System.currentTimeMillis(),
                            verification = VerificationStatus.OBSERVED,
                        ),
                    )
                    ApplyResult.Applied(
                        observedDensityDpi = densityDpi,
                        observedWidthDp = DisplayMath.effectiveWidthDp(widthPx, densityDpi),
                    )
                } else {
                    repository.save(baseline.copy(verification = VerificationStatus.FAILED))
                    ApplyResult.Refused(
                        BridgeUnavailableReason(
                            code = "NOT_OBSERVED",
                            detail = "The bridge reported success but the measured density is " +
                                "${densityDpi}dpi, not ${targetDensityDpi}dpi. Treating as failure.",
                        ),
                    )
                }
            }

            else -> result
        }

        _lastResult.value = verified
        return verified
    }

    /** Puts the user's own configuration back (TZ 4.3, A12). */
    suspend fun restore(): ApplyResult {
        // first() rather than collect(): the DataStore flow never completes.
        val baseline = repository.state.first() ?: run {
            val refused = ApplyResult.Refused(
                BridgeUnavailableReason(
                    code = "NO_BASELINE",
                    detail = "No experiment baseline is recorded, so there is nothing to restore.",
                ),
            )
            _lastResult.value = refused
            return refused
        }
        val result = bridge.restore(baseline)
        if (result is ApplyResult.Applied) repository.clear()
        _lastResult.value = result
        return result
    }
}
