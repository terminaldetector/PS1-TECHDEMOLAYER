package com.metro.opticalshell.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metro.opticalshell.core.CapabilityReport
import com.metro.opticalshell.display.ApplyResult
import com.metro.opticalshell.display.DisplayMath
import com.metro.opticalshell.display.TabletVerdict
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * The diagnostics panel (TZ section 7 last bullet, A09, A10, A14).
 *
 * This screen is the project's honesty surface. It shows measured numbers, the
 * mathematical verdict on tablet geometry, and the exact operator procedure for
 * the Tier 1 experiment. It never claims a capability it has not observed.
 */
@Composable
fun DeviceCapabilityScreen(
    report: CapabilityReport,
    candidateDensities: List<Int>,
    lastResult: ApplyResult?,
    onRequestDensity: (dpi: Int, consent: Boolean) -> Unit,
    onRestore: () -> Unit,
    onRecordFreeformObservation: (Boolean) -> Unit,
    onCopyReport: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    var pendingDensity by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MetroColors.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = "←",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .clickable(onClickLabel = "Back", onClick = onBack)
                    .padding(end = 14.dp),
            )
            Text(text = "diagnostics", style = MaterialTheme.typography.headlineMedium)
        }

        Section("measured geometry")
        report.lines().forEach { (k, v) -> KeyValue(k, v) }

        Section("tablet geometry verdict")
        val f = report.feasibility
        Mono(f.summary())
        Text(
            text = when (f.verdict) {
                TabletVerdict.ALREADY_WIDE_ENOUGH ->
                    "This window is already at or above the target width. No density change needed."

                TabletVerdict.REACHABLE_WITHIN_GUARD ->
                    "${f.targetWidthDp}dp is reachable at ${f.requiredDensityDpi}dpi, which stays " +
                        "within the readability guard. Everything will appear " +
                        "${(f.shrinkFactor * 100).toInt()}% of its current physical size."

                TabletVerdict.REACHABLE_ONLY_BELOW_GUARD ->
                    "${f.targetWidthDp}dp is only reachable by going below the readability guard " +
                        "(${f.minSafeDensityDpi}dpi). Text and touch targets would get " +
                        "uncomfortably small. The widest comfortable width is ${f.maxSafeWidthDp}dp."

                TabletVerdict.NOT_REACHABLE ->
                    "This panel cannot reach a useful tablet width without unacceptable " +
                        "readability loss. The shell stays fully usable; system-wide tablet " +
                        "adaptation is not available here."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        Section("display bridge")
        Text(text = report.displayBridgeStatus, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Raising a DPI number does not create a tablet. To gain logical width at a " +
                "fixed pixel width, density must be LOWERED, which shrinks everything on screen. " +
                "WRITE_SETTINGS does not grant this; density is not a WRITE_SETTINGS value.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
            color = MetroColors.Dim,
        )

        Section("candidate densities")
        Text(
            text = "Derived from this device's stock ${report.readout.densityDpi}dpi, not a " +
                "hard-coded list. Tapping one asks for confirmation first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.Dim,
        )
        candidateDensities.forEach { dpi ->
            val widthDp = DisplayMath.effectiveWidthDp(report.readout.maxWidthPx, dpi)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Request ${dpi}dpi") { pendingDensity = dpi }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Mono("${dpi}dpi")
                Mono("${widthDp}dp")
            }
        }

        pendingDensity?.let { dpi ->
            ConsentBlock(
                dpi = dpi,
                widthDp = DisplayMath.effectiveWidthDp(report.readout.maxWidthPx, dpi),
                shrinkPercent = (DisplayMath.shrinkFactor(report.readout.densityDpi, dpi) * 100).toInt(),
                accent = accent,
                onConfirm = {
                    onRequestDensity(dpi, true)
                    pendingDensity = null
                },
                onCancel = { pendingDensity = null },
            )
        }

        lastResult?.let { ResultBlock(it) }

        Section("rollback")
        Text(
            text = "Restore returns YOUR previous configuration, not a factory number. If no " +
                "override existed, the correct restore is `wm density reset`. There is no " +
                "guaranteed automatic undo after a crash - see docs/DISPLAY_ROLLBACK.md.",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.Dim,
        )
        Action("restore display configuration", accent, onRestore)

        Section("freeform observation")
        Text(
            text = "The shell cannot see other apps' windows without Launcher/Recents " +
                "privileges, so OBSERVED can only be recorded by a person looking at the " +
                "screen. Launch an app in window mode from the desktop, then record what " +
                "actually happened.",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.Dim,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Action("a window appeared", accent) { onRecordFreeformObservation(true) }
            Box(Modifier.padding(start = 10.dp)) {
                Action("it was full-screen", MetroColors.Divider) { onRecordFreeformObservation(false) }
            }
        }

        Section("report")
        Action("copy full report", accent) { onCopyReport(report.asPlainText()) }
        Box(Modifier.padding(bottom = 40.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = LocalAccent.current,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(text = key, style = MaterialTheme.typography.bodyMedium, color = MetroColors.Dim)
        Mono(value)
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MetroColors.White,
    )
}

/** TZ 15.7: a separate, explicit confirmation immediately before the action. */
@Composable
private fun ConsentBlock(
    dpi: Int,
    widthDp: Int,
    shrinkPercent: Int,
    accent: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(14.dp),
    ) {
        Text(text = "confirm display change", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Target ${dpi}dpi gives ${widthDp}dp of logical width. Everything on screen " +
                "becomes about $shrinkPercent% of its current physical size. This affects the " +
                "WHOLE system, not just this shell. Your current configuration is recorded " +
                "first so it can be restored.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row {
            Action("I understand, continue", accent, onConfirm)
            Box(Modifier.padding(start = 10.dp)) {
                Action("cancel", MetroColors.Divider, onCancel)
            }
        }
    }
}

@Composable
private fun ResultBlock(result: ApplyResult) {
    Section("last attempt")
    when (result) {
        is ApplyResult.Refused -> {
            Mono("REFUSED (${result.reason.code})")
            Text(result.reason.detail, style = MaterialTheme.typography.bodyMedium)
        }

        is ApplyResult.ManualProcedureRequired -> {
            Mono("MANUAL PROCEDURE REQUIRED")
            Text(result.note, style = MaterialTheme.typography.bodyMedium)
            if (result.applyCommands.isNotEmpty()) {
                Text(
                    "apply:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetroColors.Dim,
                    modifier = Modifier.padding(top = 8.dp),
                )
                result.applyCommands.forEach { Mono(it) }
            }
            Text(
                "restore:",
                style = MaterialTheme.typography.bodyMedium,
                color = MetroColors.Dim,
                modifier = Modifier.padding(top = 8.dp),
            )
            result.restoreCommands.forEach { Mono(it) }
        }

        is ApplyResult.Applied -> {
            Mono("APPLIED and OBSERVED")
            Mono("density=${result.observedDensityDpi}dpi width=${result.observedWidthDp}dp")
        }
    }
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .background(color)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
