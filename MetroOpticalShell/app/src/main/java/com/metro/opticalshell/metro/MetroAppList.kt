package com.metro.opticalshell.metro

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors
import kotlinx.coroutines.launch

/**
 * The WP8 "all apps" list: alphabetical, with letter headers and search
 * (TZ 3.3, A04).
 *
 * Shows REAL launchable activities from PackageManager. A long press offers
 * pin/unpin, app info and uninstall-request.
 */
@Composable
fun MetroAppList(
    apps: List<InstalledApp>,
    query: String,
    onQueryChange: (String) -> Unit,
    iconFor: (InstalledApp) -> android.graphics.drawable.Drawable?,
    onLaunch: (InstalledApp) -> Unit,
    onLongPress: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var jumpOpen by remember { mutableStateOf(false) }

    // Buckets and header row indices, computed once per list (TZ 12).
    val grouped = remember(apps) { JumpList.groupedInOrder(apps) }
    val jumpTargets = remember(apps) { JumpList.jumpGrid(apps) }

    Column(modifier = modifier.fillMaxSize()) {
        MetroSearchField(
            query = query,
            onQueryChange = onQueryChange,
            accent = accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isBlank()) "Loading apps..." else "Nothing matches \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                grouped.forEach { (letter, bucket) ->
                    item(key = "hdr-$letter") {
                        LetterHeader(letter, accent) { jumpOpen = true }
                    }
                    items(bucket, key = { "${it.packageName}/${it.activityName}" }) { app ->
                        AppRow(app, iconFor(app), onLaunch, onLongPress)
                    }
                }
            } else {
                items(apps, key = { "${it.packageName}/${it.activityName}" }) { app ->
                    AppRow(app, iconFor(app), onLaunch, onLongPress)
                }
            }
        }
    }

    // The WP jump list: the whole alphabet, empty letters greyed rather than
    // hidden, so the grid never reflows as apps are installed (TZ 3.5).
    if (jumpOpen) {
        JumpGrid(
            targets = jumpTargets,
            accent = accent,
            onPick = { target ->
                jumpOpen = false
                target.scrollIndex?.let { index ->
                    scope.launch { listState.animateScrollToItem(index) }
                }
            },
            onDismiss = { jumpOpen = false },
        )
    }
}

@Composable
private fun JumpGrid(
    targets: List<JumpTarget>,
    accent: Color,
    onPick: (JumpTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .clickable(onClickLabel = "Close jump list", onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.padding(24.dp),
        ) {
            items(targets, key = { it.letter }) { target ->
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(64.dp)
                        .background(if (target.enabled) accent else Color(0xFF1C1C1C))
                        .clickable(
                            enabled = target.enabled,
                            onClickLabel = "Jump to ${target.letter}",
                        ) { onPick(target) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = target.letter.toString(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Light,
                        color = if (target.enabled) MetroColors.White else Color(0xFF555555),
                    )
                }
            }
        }
    }
}

@Composable
private fun LetterHeader(letter: Char, accent: Color, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .padding(start = 16.dp, top = 18.dp, bottom = 6.dp)
            .size(36.dp)
            .background(accent)
            .clickable(onClickLabel = "Jump to letter") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            color = MetroColors.White,
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    icon: android.graphics.drawable.Drawable?,
    onLaunch: (InstalledApp) -> Unit,
    onLongPress: (InstalledApp) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Launch ${app.label}") { onLaunch(app) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            val painter = remember(icon) { BitmapPainter(icon.toBitmap(72, 72).asImageBitmap()) }
            Image(painter = painter, contentDescription = null, modifier = Modifier.size(36.dp))
        } else {
            Box(
                modifier = Modifier.size(36.dp).background(MetroColors.TileFallback),
                contentAlignment = Alignment.Center,
            ) {
                Text(app.label.take(1).uppercase(), color = MetroColors.White)
            }
        }

        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "⋯",
                color = MetroColors.Dim,
                modifier = Modifier
                    .clickable(onClickLabel = "Options for ${app.label}") { onLongPress(app) }
                    .padding(8.dp),
            )
        }
    }
}

/** Flat, square, accent-underlined input. WP8 had no rounded fields. */
@Composable
fun MetroSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    placeholder: String = "search",
) {
    Box(
        modifier = modifier
            .background(Color(0xFF141414))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (query.isEmpty()) {
            Text(text = placeholder, color = MetroColors.Dim, fontSize = 15.sp)
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = MetroColors.White, fontSize = 15.sp),
            cursorBrush = SolidColor(accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
