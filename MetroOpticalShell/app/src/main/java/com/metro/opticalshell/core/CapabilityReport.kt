package com.metro.opticalshell.core

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.metro.opticalshell.display.DisplayMetricsReader
import com.metro.opticalshell.display.DisplayReadout
import com.metro.opticalshell.display.TabletFeasibility
import com.metro.opticalshell.runtime.FreeformCapability
import com.metro.opticalshell.runtime.FreeformLaunchAdapter

/**
 * Honest status vocabulary for every subsystem (TZ section 14).
 *
 * The distinction that matters: IMPLEMENTED means code exists; TESTED means an
 * automated test covers it; DEVICE_VERIFIED means someone watched it work on
 * real hardware. Compiling is not testing and testing is not device
 * verification (TZ 13).
 */
enum class SubsystemStatus {
    IMPLEMENTED,
    TESTED,
    DEVICE_VERIFIED,
    PARTIAL,
    BLOCKED,
    NOT_STARTED,
}

/**
 * Everything the shell has actually measured about this device
 * (TZ 5.2 `SystemCapabilityProbe`, A09).
 */
data class CapabilityReport(
    val androidRelease: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    /** True when the ROM self-identifies as HyperOS/MIUI. Informational only. */
    val looksLikeHyperOs: Boolean,
    val readout: DisplayReadout,
    val feasibility: TabletFeasibility,
    val freeform: FreeformCapability,
    val isDefaultHome: Boolean,
    val displayBridgeStatus: String,
    val multiWindowModeNow: Boolean,
) {
    /**
     * Note that [looksLikeHyperOs] is never used to DECIDE anything.
     * TZ 5.3 is explicit: freeform/desktop availability is determined by
     * probing, not by the ROM's name.
     */
    fun lines(): List<Pair<String, String>> = listOf(
        "Device" to "$manufacturer $model ($device)",
        "Android" to "$androidRelease (API $apiLevel)",
        "ROM hints" to if (looksLikeHyperOs) "MIUI/HyperOS properties present" else "none detected",
        "Window px" to "${readout.windowWidthPx} x ${readout.windowHeightPx}",
        "Display px" to "${readout.maxWidthPx} x ${readout.maxHeightPx}",
        "densityDpi" to "${readout.densityDpi} (density ${readout.density})",
        "Font scale" to readout.fontScale.toString(),
        "Window dp" to "${readout.windowWidthDp} x ${readout.windowHeightDp}",
        "Display width dp" to "${readout.maxWidthDp}",
        "WindowSizeClass" to readout.widthClass.name,
        "Insets px" to "t=${readout.insetTopPx} b=${readout.insetBottomPx} " +
            "l=${readout.insetLeftPx} r=${readout.insetRightPx} cutout=${readout.cutoutTopPx}",
        "Tablet feasibility" to feasibility.summary(),
        "Freeform" to "${freeform.status.name} - ${freeform.detail}",
        "Multi-window now" to multiWindowModeNow.toString(),
        "Default home" to isDefaultHome.toString(),
        "Display bridge" to displayBridgeStatus,
    )

    fun asPlainText(): String = lines().joinToString("\n") { (k, v) -> "$k: $v" }
}

object SystemCapabilityProbe {

    fun probe(
        activity: Activity,
        freeformAdapter: FreeformLaunchAdapter,
        displayBridgeStatus: String,
        targetWidthDp: Int,
    ): CapabilityReport {
        val readout = DisplayMetricsReader.read(activity)
        return CapabilityReport(
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            device = Build.DEVICE ?: "unknown",
            looksLikeHyperOs = detectMiuiFamily(),
            readout = readout,
            feasibility = readout.feasibility(targetWidthDp),
            freeform = freeformAdapter.probe(),
            isDefaultHome = isDefaultHome(activity),
            displayBridgeStatus = displayBridgeStatus,
            multiWindowModeNow = activity.isInMultiWindowMode,
        )
    }

    /** Informational ROM sniff. Never gates behaviour (TZ 5.3). */
    private fun detectMiuiFamily(): Boolean {
        val props = listOf("ro.miui.ui.version.name", "ro.mi.os.version.name")
        return props.any { key ->
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
                val value = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                value.isNotEmpty()
            }.getOrDefault(false)
        }
    }

    fun isDefaultHome(context: Context): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return resolved?.activityInfo?.packageName == context.packageName
    }
}
