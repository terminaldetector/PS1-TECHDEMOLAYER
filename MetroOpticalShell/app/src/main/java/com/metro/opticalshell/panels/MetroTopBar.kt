package com.metro.opticalshell.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metro.opticalshell.metro.rememberBatteryInfo
import com.metro.opticalshell.metro.rememberClockText
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * The Metro status strip, INSIDE our own window (TZ §6.1).
 *
 * The boundary this type respects: it is the shell's own header, not a
 * replacement for the Android status bar. An ordinary app cannot replace the
 * status bar (see OP02_SYSTEMUI_WM_SHELL_FEASIBILITY.md), so this strip only
 * ever draws within the shell's Activity and the real system bar stays exactly
 * where it is.
 *
 * It also does not duplicate the notification shade on top of the system's: TZ
 * §6.1 warns that two competing swipes or invisible clicks are worse than one
 * panel, so the Action Center is opened by tapping the strip, never by a swipe
 * that would fight the system's own.
 */
@Composable
fun MetroTopBar(
    onOpenActionCenter: () -> Unit,
    modifier: Modifier = Modifier,
    showClock: Boolean = true,
    showBattery: Boolean = true,
    notificationCount: Int? = null,
) {
    val time by rememberClockText()
    val battery by rememberBatteryInfo()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Color(0xFF0A0A0A))
            // A tap, not a swipe: the swipe belongs to the system.
            .clickable(onClickLabel = "Open Action Center", onClick = onOpenActionCenter)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        // A badge appears only when we actually know a count. Null means no
        // notification access, and then nothing is shown (TZ 3.3).
        notificationCount?.let { count ->
            if (count > 0) {
                Text(
                    text = count.toString(),
                    fontSize = 11.sp,
                    color = LocalAccent.current,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
        }
        if (showBattery && battery.levelPercent >= 0) {
            Text(
                text = "${battery.levelPercent}%" + if (battery.charging) "+" else "",
                fontSize = 11.sp,
                color = MetroColors.Dim,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        if (showClock) {
            Text(text = time, fontSize = 11.sp, color = MetroColors.White)
        }
    }
}

/**
 * The WP8.1-style Action Center (TZ §6.1, §3.7).
 *
 * Honest about every limit:
 *  - notification rows appear only with granted access, and the consent
 *    rationale is shown instead when it is absent;
 *  - quick actions that have no public API **hand over** to the system's own
 *    panels rather than rendering fake toggles;
 *  - it never suppresses a system warning.
 */
@Composable
fun MetroActionCenter(
    visible: Boolean,
    notificationAccessGranted: Boolean,
    notifications: List<NotificationSummary>,
    labelFor: (String) -> String,
    panelStatus: String,
    onRequestNotificationAccess: () -> Unit,
    onOpenSystemShade: () -> Unit,
    onOpenSystemQuickSettings: () -> Unit,
    onOpenShellSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))
                .clickable(onClickLabel = "Close", onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .background(Color(0xFF0D0D0D))
                    .padding(16.dp),
            ) {
                Text("action center", style = MaterialTheme.typography.headlineMedium)

                // --- quick actions -----------------------------------------
                Text(
                    text = "quick actions",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                Text(
                    text = "These open Android's own panels. The shell does not " +
                        "replace system quick settings, and does not show toggles " +
                        "it cannot actually operate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetroColors.Dim,
                )
                ActionRow("open system notifications", accent, onOpenSystemShade)
                ActionRow("open system quick settings", accent, onOpenSystemQuickSettings)
                ActionRow("shell settings", MetroColors.Divider, onOpenShellSettings)

                Text(
                    text = panelStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetroColors.Dim,
                    modifier = Modifier.padding(top = 8.dp),
                )

                // --- notifications -----------------------------------------
                Text(
                    text = "notifications",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                )

                if (!notificationAccessGranted) {
                    Text(
                        text = NotificationAccess.RATIONALE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MetroColors.Dim,
                    )
                    ActionRow("grant notification access", accent, onRequestNotificationAccess)
                } else if (notifications.isEmpty()) {
                    Text(
                        text = "Nothing right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MetroColors.Dim,
                    )
                } else {
                    notifications.forEach { summary ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = labelFor(summary.packageName),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = summary.count.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = accent,
                            )
                        }
                    }
                    Text(
                        text = "Counts only. The shell does not retain message " +
                            "contents and sends nothing off the device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MetroColors.Dim,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Box(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 10.dp)
            .background(color)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
