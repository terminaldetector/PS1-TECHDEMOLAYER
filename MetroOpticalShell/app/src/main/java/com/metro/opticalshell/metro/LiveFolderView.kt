package com.metro.opticalshell.metro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * A closed folder's face on the Start surface (TZ §3.4).
 *
 * Shows a preview of up to four member tiles as small squares, which is how
 * WP8.1 Update signalled a folder, plus the folder's name.
 */
@Composable
fun FolderTileFace(
    name: String,
    previewAccents: List<Color>,
    unit: Dp,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = unit.value.dp * 0.12f),
            verticalArrangement = Arrangement.Center,
        ) {
            // Two rows of two: the folder "grid of grids" look.
            previewAccents.chunked(2).take(2).forEach { row ->
                Row {
                    row.forEach { c ->
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size((unit.value * 0.3f).dp)
                                .background(c),
                        )
                    }
                }
            }
            if (previewAccents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .size((unit.value * 0.3f).dp)
                        .background(accent),
                )
            }
        }
        Text(
            text = name,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 6.dp, end = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An opened Live Folder (TZ §3.4).
 *
 * Opens in place on Start with a scale animation, holds the real tile grid for
 * its members, and allows rename, reorder, resize and removal. Nesting is
 * capped at [FolderOps.MAX_NESTING], and dropping the second-to-last tile out
 * dissolves the folder rather than leaving a one-item container.
 */
@Composable
fun LiveFolderOverlay(
    visible: Boolean,
    folderName: String,
    tiles: List<TileSpec>,
    columns: Int,
    iconForPackage: (String) -> android.graphics.drawable.Drawable?,
    onRename: (String) -> Unit,
    onLaunch: (TileSpec) -> Unit,
    onRemoveFromFolder: (String) -> Unit,
    onResize: (String, TileSize) -> Unit,
    onReorder: (String, Int) -> Unit,
    onDissolve: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalAccent.current
    var editing by remember(folderName) { mutableStateOf(false) }
    var nameDraft by remember(folderName) { mutableStateOf(folderName) }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(animationSpec = tween(180), initialScale = 0.85f),
        exit = scaleOut(animationSpec = tween(140), targetScale = 0.9f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2000000)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "←",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .clickable(onClickLabel = "Close folder", onClick = onDismiss)
                            .padding(end = 14.dp),
                    )
                    if (editing) {
                        BasicTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            singleLine = true,
                            textStyle = TextStyle(color = MetroColors.White, fontSize = 26.sp),
                            cursorBrush = SolidColor(accent),
                            modifier = Modifier.fillMaxWidth(0.6f),
                        )
                        Text(
                            text = "save",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .background(accent)
                                .clickable(onClickLabel = "Save name") {
                                    onRename(nameDraft)
                                    editing = false
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.clickable(onClickLabel = "Rename folder") {
                                editing = true
                            },
                        )
                    }
                }

                MetroTileGrid(
                    tiles = tiles,
                    columns = columns,
                    editMode = false,
                    modifier = Modifier.weight(1f),
                    onTileClick = onLaunch,
                    onResize = onResize,
                    onReorder = onReorder,
                    onUnpin = onRemoveFromFolder,
                    presentationName = "FOLDER",
                ) { placed, unit ->
                    MetroTileView(
                        tile = placed,
                        unit = unit,
                        icon = placed.spec.packageName?.let(iconForPackage),
                    )
                }

                Row(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "dissolve folder",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .background(MetroColors.Divider)
                            .clickable(onClickLabel = "Dissolve folder", onClick = onDissolve)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
