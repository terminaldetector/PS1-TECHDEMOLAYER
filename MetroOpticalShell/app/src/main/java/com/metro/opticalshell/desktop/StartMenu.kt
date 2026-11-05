package com.metro.opticalshell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.metro.opticalshell.metro.MetroAppList
import com.metro.opticalshell.metro.MetroSearchField
import com.metro.opticalshell.metro.MetroTileGrid
import com.metro.opticalshell.metro.MetroTileView
import com.metro.opticalshell.metro.TileSpec
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * The Start panel of the desktop presentation (TZ 2.3, 5.1).
 *
 * It shows the SAME tiles as the portrait presentations, in a narrower column
 * count, plus the same app list. One collection, three views (TZ 0, A05).
 *
 * Keyboard: Esc closes, typing filters, Enter launches the first result
 * (TZ section 7). No system-reserved shortcut is intercepted.
 */
@Composable
fun StartMenu(
    tiles: List<TileSpec>,
    columns: Int,
    apps: List<InstalledApp>,
    query: String,
    displaySummary: String?,
    iconFor: (InstalledApp) -> android.graphics.drawable.Drawable?,
    iconForPackage: (String) -> android.graphics.drawable.Drawable?,
    onQueryChange: (String) -> Unit,
    onLaunchTile: (TileSpec) -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onPinApp: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
    widthDp: Int,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val focus = remember { FocusRequester() }
    var showAllApps by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .background(Color(0xFF0D0D0D))
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }

                    Key.Enter, Key.NumPadEnter -> {
                        apps.firstOrNull()?.let(onLaunchApp)
                        true
                    }

                    else -> false
                }
            },
    ) {
        MetroSearchField(
            query = query,
            onQueryChange = onQueryChange,
            accent = accent,
            placeholder = "search apps",
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )

        Text(
            text = if (showAllApps) "all apps" else "pinned tiles",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(MetroColors.Divider)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(onClickLabel = "Toggle all apps") { showAllApps = !showAllApps },
        )

        Box(modifier = Modifier.weight(1f)) {
            if (showAllApps || query.isNotBlank()) {
                MetroAppList(
                    apps = apps,
                    query = query,
                    onQueryChange = onQueryChange,
                    iconFor = iconFor,
                    onLaunch = onLaunchApp,
                    onLongPress = onPinApp,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                MetroTileGrid(
                    tiles = tiles,
                    columns = columns,
                    editMode = false,
                    modifier = Modifier.fillMaxWidth(),
                    onTileClick = onLaunchTile,
                ) { placed, unit ->
                    MetroTileView(
                        tile = placed,
                        unit = unit,
                        icon = placed.spec.packageName?.let(iconForPackage),
                        displaySummary = displaySummary,
                    )
                }
            }
        }
    }
}
