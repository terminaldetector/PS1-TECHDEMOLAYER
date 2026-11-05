package com.metro.opticalshell.compat

import com.metro.opticalshell.display.DisplayReadout

/**
 * Records what actually happened when an app was launched (TZ §5.2).
 *
 * This is the instrument that fills OP02_APP_COMPATIBILITY_MATRIX.csv. It takes
 * measurements the shell can make itself, and leaves everything it cannot
 * determine as null rather than guessing — TZ §5.2 is explicit that manifest
 * hints are a hypothesis and measured results outrank them, and TZ §11.4
 * requires `observed_layout = unknown` unless proved by a capture.
 *
 * What the shell CAN measure by itself: its own window geometry at launch time,
 * the density, and which landscape mode it used.
 *
 * What it CANNOT: whether the other app's layout changed. It cannot see inside
 * another app's window. So [recordLaunch] always leaves `layoutChanged` null,
 * and only [recordHumanObservation] can set it.
 */
object AppLayoutProbe {

    /**
     * Records a launch. `layoutChanged` is deliberately not set: the shell has
     * no way to know, and a default of `false` would be a lie that classifies
     * the app as B.
     */
    fun recordLaunch(
        profile: AppCompatibilityProfile,
        readout: DisplayReadout,
        mode: LandscapeMode,
        nowMillis: Long,
        deviceBuild: String? = null,
    ): AppCompatibilityProfile {
        val observation = LayoutObservation(
            atMillis = nowMillis,
            windowWidthDp = readout.windowWidthDp,
            windowHeightDp = readout.windowHeightDp,
            densityDpi = readout.densityDpi,
            landscapeMode = mode,
            layoutChanged = null,
            evidence = null,
            deviceBuild = deviceBuild,
            note = "Launch recorded by the shell. Layout change is unknown: the " +
                "shell cannot see inside another app's window.",
        )
        return withObservation(profile, observation)
    }

    /**
     * Records what a person saw. The ONLY route to a determined result, exactly
     * as `FreeformStatus.OBSERVED` is in the runtime layer.
     */
    fun recordHumanObservation(
        profile: AppCompatibilityProfile,
        layoutChanged: Boolean,
        evidence: String,
        textStillReadable: Boolean? = null,
        recreatedOnRotate: Boolean? = null,
        nowMillis: Long = 0L,
    ): AppCompatibilityProfile {
        require(evidence.isNotBlank()) {
            "an observation without evidence may not be recorded (TZ 11.4)"
        }
        val last = profile.observations.lastOrNull()
        val observation = LayoutObservation(
            atMillis = nowMillis,
            windowWidthDp = last?.windowWidthDp ?: 0,
            windowHeightDp = last?.windowHeightDp ?: 0,
            densityDpi = last?.densityDpi ?: 0,
            landscapeMode = last?.landscapeMode ?: LandscapeMode.FOCUSED_FULLSCREEN,
            layoutChanged = layoutChanged,
            recreatedOnRotate = recreatedOnRotate,
            textStillReadable = textStillReadable,
            evidence = evidence,
            deviceBuild = last?.deviceBuild,
            note = "Recorded by an operator.",
        )
        return withObservation(profile, observation)
    }

    private fun withObservation(
        profile: AppCompatibilityProfile,
        observation: LayoutObservation,
    ): AppCompatibilityProfile {
        val observations = (profile.observations + observation).takeLast(MAX_OBSERVATIONS)
        return profile.copy(
            observations = observations,
            // Re-derive the class from evidence on every change.
            observedClass = LandscapePolicyEngine.classify(observations),
        )
    }

    /** One CSV row in the format TZ §11.4 specifies. */
    fun csvRow(
        profile: AppCompatibilityProfile,
        scenario: String,
        api: Int,
        displayMode: String,
        systemGestures: String = "NOT_TESTED",
        panelStatus: String = "NOT_TESTED",
    ): String {
        val last = profile.observations.lastOrNull()
        val observed = when (last?.layoutChanged) {
            true -> "changed"
            false -> "unchanged"
            null -> "unknown"
        }
        val result = when {
            last == null -> "NOT_RUN"
            last.layoutChanged == true && last.evidence != null -> "TABLET_LAYOUT_VERIFIED"
            last.layoutChanged == false && last.evidence != null -> "PHONE_LAYOUT_CONFIRMED"
            else -> "UNVERIFIED"
        }
        return listOf(
            scenario,
            last?.deviceBuild ?: "NOT_COLLECTED",
            if (api > 0) api.toString() else "NOT_COLLECTED",
            displayMode,
            profile.packageName,
            profile.preferredLayout.name,
            observed,
            last?.landscapeMode?.name ?: "NOT_RUN",
            systemGestures,
            panelStatus,
            result,
            last?.evidence ?: "none",
            profile.observedClass?.name ?: "unclassified",
        ).joinToString(",")
    }

    const val MAX_OBSERVATIONS = 20
}
