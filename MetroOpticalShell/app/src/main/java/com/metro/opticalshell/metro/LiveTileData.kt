package com.metro.opticalshell.metro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live tile data for Operation 01 (TZ 3.4).
 *
 * Only data we genuinely have: wall clock, date, battery and window geometry.
 * There are deliberately NO weather, notification-count or now-playing tiles:
 * TZ 3.4 forbids depicting those without a real source and the matching
 * permission, and inventing them would be the exact dishonesty TZ 13 rules out.
 *
 * Update frequency is capped and every updater is composition-scoped, so
 * nothing ticks while the shell is not visible. No background service exists
 * (TZ 12).
 */

/** Ticks once per [periodMillis] while composed, and not a moment longer. */
@Composable
fun rememberTicker(periodMillis: Long = 1_000L): State<Long> {
    val state = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            state.value = System.currentTimeMillis()
            // Align to the next boundary so the clock does not drift visibly.
            val now = System.currentTimeMillis()
            delay(periodMillis - (now % periodMillis))
        }
    }
    return state
}

/** Clock tile text. Minute resolution: a seconds tick would be wasted work. */
@Composable
fun rememberClockText(pattern: String = "HH:mm"): State<String> {
    val tick = rememberTicker(60_000L)
    val formatter = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    val state = remember { mutableStateOf(formatter.format(Date())) }
    LaunchedEffect(tick.value) { state.value = formatter.format(Date(tick.value)) }
    return state
}

@Composable
fun rememberDateText(pattern: String = "EEEE\nd MMMM"): State<String> {
    val tick = rememberTicker(60_000L)
    val formatter = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    val state = remember { mutableStateOf(formatter.format(Date())) }
    LaunchedEffect(tick.value) { state.value = formatter.format(Date(tick.value)) }
    return state
}

data class BatteryInfo(val levelPercent: Int, val charging: Boolean)

/**
 * Battery level from ACTION_BATTERY_CHANGED.
 *
 * This is a sticky broadcast and needs no permission. The receiver is
 * registered and unregistered with the composition.
 */
@Composable
fun rememberBatteryInfo(): State<BatteryInfo> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(BatteryInfo(-1, false)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                state.value = BatteryInfo(
                    levelPercent = percent,
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                )
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Sticky broadcast: this returns the current value immediately.
        val sticky = context.registerReceiver(receiver, filter)
        sticky?.let { receiver.onReceive(context, it) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return state
}
