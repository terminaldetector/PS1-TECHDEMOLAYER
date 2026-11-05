package com.metro.opticalshell.metro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WP8-style edit affordances (TZ 3.3): resize cycles through the four sizes,
 * unpin removes the TILE and never the app.
 */
@Composable
fun TileEditOverlay(
    tile: PlacedTile,
    onResize: (TileSize) -> Unit,
    onUnpin: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            // Resize: cycles SMALL -> MEDIUM -> WIDE -> LARGE -> SMALL.
            EditChip(
                glyph = "⤢",
                description = "Resize ${tile.spec.label}",
                onClick = { onResize(tile.spec.size.next()) },
            )
            Box(Modifier.size(6.dp))
            // Unpin: removes the tile from Start. The app stays installed.
            EditChip(
                glyph = "✕",
                description = "Unpin ${tile.spec.label}",
                onClick = onUnpin,
            )
        }

        Text(
            text = tile.spec.size.name,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
            fontSize = 9.sp,
            color = Color(0xCCFFFFFF),
        )
    }
}

@Composable
private fun EditChip(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xE6FFFFFF))
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = Color.Black,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The edit-mode state holder (TZ 3.3).
 *
 * Kept deliberately small: entering edit mode is a UI concern, while the tile
 * data it edits lives in TileRepository. Edit mode is therefore NOT persisted —
 * a relaunch should come back to a normal start screen.
 */
class TileEditController {
    var editing: Boolean = false
        private set

    var selectedTileId: String? = null
        private set

    fun enter(tileId: String?) {
        editing = true
        selectedTileId = tileId
    }

    fun exit() {
        editing = false
        selectedTileId = null
    }

    fun select(tileId: String?) {
        selectedTileId = tileId
    }
}
