package com.metro.opticalshell.metro

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * One tile. Flat accent fill, white type, no rounding and no blur (TZ 3.1).
 *
 * The label sits bottom-left exactly as WP8 did, and only on tiles big enough
 * to carry it; a 1x1 tile shows the icon alone.
 */
@Composable
fun MetroTileView(
    tile: PlacedTile,
    unit: Dp,
    icon: Drawable?,
    displaySummary: String? = null,
) {
    val accent = tile.spec.accentArgb?.let { Color(it) } ?: LocalAccent.current
    val spec = tile.spec
    val shared = LocalStartBackground.current

    // TZ 3.2: in WP81_IMAGE_MASK the tile body must be fully clear so the ONE
    // shared Start Background shows through. That clear body is precisely what
    // separates the historical effect from TRANSLUCENT_ACCENT, which is a
    // deliberate user choice rather than an imitation of it.
    val fillAlpha = TileBackgroundMath.accentAlpha(shared.mode)
    val showScrim = shared.isActive && tile.w >= 2 && tile.h >= 2

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (fillAlpha > 0f) {
                    Modifier.background(accent.copy(alpha = fillAlpha))
                } else {
                    Modifier
                },
            )
            .tileLabelScrim(showScrim),
    ) {
        when (spec.live) {
            LiveTileKind.CLOCK -> ClockTileContent(spec)
            LiveTileKind.DATE -> DateTileContent(spec)
            LiveTileKind.BATTERY -> BatteryTileContent(spec)
            LiveTileKind.DISPLAY_INFO -> DisplayTileContent(spec, displaySummary)
            LiveTileKind.STATIC -> StaticTileContent(spec, tile, unit, icon)
        }
    }
}

@Composable
private fun StaticTileContent(spec: TileSpec, tile: PlacedTile, unit: Dp, icon: Drawable?) {
    Box(modifier = Modifier.fillMaxSize()) {
        val iconSize = (unit.value * 0.62f).dp.coerceAtMost(72.dp)
        if (icon != null) {
            val painter = remember(icon) {
                BitmapPainter(icon.toBitmap(96, 96).asImageBitmap())
            }
            Image(
                painter = painter,
                contentDescription = spec.label,
                modifier = Modifier
                    .align(if (tile.w >= 2) Alignment.Center else Alignment.Center)
                    .size(iconSize),
            )
        } else {
            Text(
                text = spec.label.take(1).uppercase(),
                modifier = Modifier.align(Alignment.Center),
                fontSize = (unit.value * 0.45f).sp,
                fontWeight = FontWeight.Light,
                color = MetroColors.White,
            )
        }

        // WP8 only labelled tiles with room for a label.
        if (tile.w >= 2 && tile.h >= 2) {
            Text(
                text = spec.label,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 6.dp, end = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClockTileContent(spec: TileSpec) {
    val time by rememberClockText()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(text = time, style = MaterialTheme.typography.displayLarge)
        Text(text = spec.label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DateTileContent(spec: TileSpec) {
    val date by rememberDateText()
    Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            text = date,
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = spec.label,
            modifier = Modifier.align(Alignment.BottomStart),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BatteryTileContent(spec: TileSpec) {
    val battery by rememberBatteryInfo()
    Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            text = if (battery.levelPercent >= 0) "${battery.levelPercent}%" else "--",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = if (battery.charging) "${spec.label} - charging" else spec.label,
            modifier = Modifier.align(Alignment.BottomStart),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shows the real measured geometry (TZ A09). If the caller has no readout yet
 * it says so rather than printing a plausible-looking number.
 */
@Composable
private fun DisplayTileContent(spec: TileSpec, summary: String?) {
    Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            text = summary ?: "measuring...",
            modifier = Modifier.align(Alignment.CenterStart),
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.White,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = spec.label,
            modifier = Modifier.align(Alignment.BottomStart),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
