package com.metro.opticalshell.compat

import kotlinx.serialization.Serializable

/** What layout the user wants an app to get (TZ §5.1). */
enum class LayoutIntent { AUTO, PHONE, TABLET, DESKTOP }

/** How the user wants it launched (TZ §5.1). */
enum class WindowIntent { AUTO, FULLSCREEN, WINDOWED, SPLIT }

/** Rotation handling (TZ §5.1, §5.3). */
enum class RotationPolicy {
    /** Never fight the app. The default, and the only safe one. */
    RESPECT_APP,

    /** Let the shell rotate, but keep the app's own stable configuration. */
    KEEP_STABLE_CONFIGURATION,

    /** The user accepted that this app is recreated on rotation. */
    ALLOW_RECREATION,
}

/**
 * The two equal ways of working inside ONE `EXPANDED_LANDSCAPE` (TZ §4).
 *
 * These are launch styles, not environments. Switching between them does not
 * create a second workspace, and both keep the Metro desktop reachable.
 */
enum class LandscapeMode {
    /** One app across the usable landscape screen. Always available. */
    FOCUSED_FULLSCREEN,

    /** Windows and task switching. Only where the system really supports it. */
    MULTI_WINDOW_DESKTOP,
}

/**
 * The app classes of TZ §5.3.
 *
 * A class is a *measured conclusion*, never a guess from the manifest. TZ §5.2
 * is explicit: manifest flags are a hypothesis, and observed results outrank
 * them. [AppCompatibilityProfile.observedClass] therefore starts null.
 */
enum class AppCompatClass {
    /** A — adaptive. Real tablet/desktop layout at width. */
    A_ADAPTIVE,

    /** B — resizable but the design stays phone-shaped. */
    B_RESIZABLE_PHONE_UI,

    /** C — portrait-locked. */
    C_PORTRAIT_LOCKED,

    /** D — unstable on rotation. */
    D_UNSTABLE_ON_ROTATE,

    /** E — games / camera / protected pipelines. Do not interfere. */
    E_PROTECTED_PIPELINE,

    /** F — OEM-specific behaviour on this ROM. */
    F_DEVICE_SPECIFIC,
}

/**
 * Per-app intentions and measurements (TZ §5.1).
 *
 * This is a model of INTENT plus RECORDED FACT. It is not a promise: the shell
 * applies only the adapters that are actually available, and a third-party app
 * may ignore every one of them.
 */
@Serializable
data class AppCompatibilityProfile(
    val packageName: String,
    val preferredLayout: LayoutIntent = LayoutIntent.AUTO,
    val preferredWindowMode: WindowIntent = WindowIntent.AUTO,
    val preferredLogicalWidthDp: Int? = null,
    val readabilityScale: Float = 1f,
    val allowLetterboxFallback: Boolean = true,
    val rotationPolicy: RotationPolicy = RotationPolicy.RESPECT_APP,
    val keepSystemBars: Boolean = true,
    val keyboardMousePreset: String? = null,
    val lastVerifiedBuild: String? = null,
    /** Null until something was actually observed on a device. */
    val observedClass: AppCompatClass? = null,
    /** Observations, newest last. Evidence for the class. */
    val observations: List<LayoutObservation> = emptyList(),
) {
    fun clamped(): AppCompatibilityProfile = copy(
        readabilityScale = readabilityScale.coerceIn(0.6f, 2.0f),
        preferredLogicalWidthDp = preferredLogicalWidthDp?.coerceIn(320, 2048),
    )

    /** True only when a human recorded a real layout change (TZ §5.4). */
    val hasVerifiedTabletLayout: Boolean
        get() = observations.any { it.layoutChanged == true && it.evidence != null }
}

/**
 * One recorded launch (TZ §5.2).
 *
 * [layoutChanged] is a nullable Boolean on purpose: `null` means "not
 * determined", which is different from "no". TZ §11.4 requires
 * `observed_layout = unknown` unless it was proved by an external capture, so
 * the type refuses to collapse the two.
 */
@Serializable
data class LayoutObservation(
    val atMillis: Long,
    val windowWidthDp: Int,
    val windowHeightDp: Int,
    val densityDpi: Int,
    val landscapeMode: LandscapeMode,
    /** null = unknown, not "no". */
    val layoutChanged: Boolean? = null,
    /** null = unknown. */
    val recreatedOnRotate: Boolean? = null,
    val textStillReadable: Boolean? = null,
    /** A screenshot path or note. Required before a success may be claimed. */
    val evidence: String? = null,
    val deviceBuild: String? = null,
    val note: String? = null,
)
