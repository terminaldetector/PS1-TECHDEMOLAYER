package com.metro.opticalshell.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metro.opticalshell.metro.LiveTileKind
import com.metro.opticalshell.metro.TileGroup
import com.metro.opticalshell.metro.TileSize
import com.metro.opticalshell.metro.TileSpec
import com.metro.opticalshell.metro.TilePackingAlgorithm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * ONE tile collection, shared by all three presentations (TZ 3.3, A05).
 *
 * There is deliberately no per-presentation copy of the tiles. Mobile and
 * Expanded render the same list through different column counts, and may carry
 * narrow per-presentation layout overrides, but never independent sets of apps
 * or data (TZ 3.3 last bullet, TZ 13).
 */
@Serializable
data class TileCollection(
    val tiles: List<TileSpec> = emptyList(),
    val groups: List<TileGroup> = emptyList(),
    /**
     * Narrow, optional per-presentation overrides (TZ 8
     * `presentationLayoutOverrides`). Key is the Presentation name, value maps
     * tileId -> TileSize. Only sizes may differ; membership never does.
     */
    val presentationSizeOverrides: Map<String, Map<String, TileSize>> = emptyMap(),
    val schemaVersion: Int = 1,
)

class TileRepository(private val context: Context) {

    private val key = stringPreferencesKey("tile_collection_v1")

    val collection: Flow<TileCollection> = context.shellDataStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map defaultCollection()
        // A corrupt or older blob must not brick the launcher.
        runCatching { ShellJson.decodeFromString<TileCollection>(raw) }
            .getOrElse { defaultCollection() }
    }

    suspend fun save(collection: TileCollection) {
        val encoded = ShellJson.encodeToString(collection)
        context.shellDataStore.edit { it[key] = encoded }
    }

    /** JSON export for backup (TZ section 8). */
    fun export(collection: TileCollection): String = ShellJson.encodeToString(collection)

    /** JSON import. Returns null when the payload is not a tile collection. */
    fun import(json: String): TileCollection? =
        runCatching { ShellJson.decodeFromString<TileCollection>(json) }.getOrNull()

    suspend fun mutate(block: (TileCollection) -> TileCollection) {
        context.shellDataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { ShellJson.decodeFromString<TileCollection>(it) }.getOrNull() }
                ?: defaultCollection()
            prefs[key] = ShellJson.encodeToString(block(current))
        }
    }

    companion object {
        /**
         * First-run start screen. Shell-owned tiles only: no fabricated
         * third-party entries, and no invented notification content (TZ 3.4).
         */
        fun defaultCollection(): TileCollection = TileCollection(
            tiles = listOf(
                TileSpec(
                    tileId = "shell.clock",
                    packageName = null,
                    label = "Clock",
                    size = TileSize.WIDE,
                    order = 0,
                    accentArgb = 0xFF2D89EF.toInt(),
                    live = LiveTileKind.CLOCK,
                ),
                TileSpec(
                    tileId = "shell.date",
                    packageName = null,
                    label = "Date",
                    size = TileSize.MEDIUM,
                    order = 1,
                    accentArgb = 0xFF00A300.toInt(),
                    live = LiveTileKind.DATE,
                ),
                TileSpec(
                    tileId = "shell.battery",
                    packageName = null,
                    label = "Battery",
                    size = TileSize.MEDIUM,
                    order = 2,
                    accentArgb = 0xFFE3A21A.toInt(),
                    live = LiveTileKind.BATTERY,
                ),
                TileSpec(
                    tileId = "shell.display",
                    packageName = null,
                    label = "Display",
                    size = TileSize.WIDE,
                    order = 3,
                    accentArgb = 0xFFB91D47.toInt(),
                    live = LiveTileKind.DISPLAY_INFO,
                ),
            ),
        )

        /** Append a launch tile for an installed app, keeping the flow dense. */
        fun withPinnedApp(
            collection: TileCollection,
            packageName: String,
            activityName: String?,
            label: String,
            size: TileSize = TileSize.MEDIUM,
        ): TileCollection {
            val tileId = "app:$packageName/${activityName ?: "default"}"
            if (collection.tiles.any { it.tileId == tileId }) return collection
            val nextOrder = (collection.tiles.maxOfOrNull { it.order } ?: -1) + 1
            return collection.copy(
                tiles = collection.tiles + TileSpec(
                    tileId = tileId,
                    packageName = packageName,
                    activityName = activityName,
                    label = label,
                    size = size,
                    order = nextOrder,
                ),
            )
        }

        /**
         * Unpin a tile. TZ 3.3: this removes the TILE, never the app.
         * Order values are re-densified so later drags stay predictable.
         */
        fun withoutTile(collection: TileCollection, tileId: String): TileCollection {
            val remaining = collection.tiles.filterNot { it.tileId == tileId }
            if (remaining.size == collection.tiles.size) return collection
            return collection.copy(
                tiles = remaining.sortedBy { it.order }
                    .mapIndexed { i, t -> t.copy(order = i) },
            )
        }

        fun withReorder(collection: TileCollection, tileId: String, targetIndex: Int) =
            collection.copy(
                tiles = TilePackingAlgorithm.reorder(collection.tiles, tileId, targetIndex),
            )

        fun withResize(collection: TileCollection, tileId: String, size: TileSize) =
            collection.copy(tiles = TilePackingAlgorithm.resize(collection.tiles, tileId, size))

        /**
         * Reconcile against the installed-app catalog (TZ 3.3).
         *
         * Tiles whose package is gone are dropped; everything the user arranged
         * by hand keeps its order and size. The catalog is never rebuilt from
         * scratch and the user's layout is never reset.
         */
        fun reconciled(
            collection: TileCollection,
            installedPackages: Set<String>,
        ): TileCollection {
            val kept = collection.tiles.filter { it.packageName == null || it.packageName in installedPackages }
            if (kept.size == collection.tiles.size) return collection
            return collection.copy(
                tiles = kept.sortedBy { it.order }.mapIndexed { i, t -> t.copy(order = i) },
            )
        }

        fun withGroup(collection: TileCollection, group: TileGroup): TileCollection {
            val others = collection.groups.filterNot { it.groupId == group.groupId }
            return collection.copy(groups = others + group)
        }

        fun withGroupRenamed(
            collection: TileCollection,
            groupId: String,
            name: String,
        ): TileCollection = collection.copy(
            groups = collection.groups.map { if (it.groupId == groupId) it.copy(name = name) else it },
        )

        /** Removing a group detaches its tiles; it never deletes them. */
        fun withoutGroup(collection: TileCollection, groupId: String): TileCollection =
            collection.copy(
                groups = collection.groups.filterNot { it.groupId == groupId },
                tiles = collection.tiles.map {
                    if (it.groupId == groupId) it.copy(groupId = null) else it
                },
            )

        fun withTileMovedToGroup(
            collection: TileCollection,
            tileId: String,
            groupId: String?,
        ): TileCollection = collection.copy(
            tiles = collection.tiles.map {
                if (it.tileId == tileId) it.copy(groupId = groupId) else it
            },
        )

        fun withAccent(
            collection: TileCollection,
            tileId: String,
            accentArgb: Int?,
        ): TileCollection = collection.copy(
            tiles = collection.tiles.map {
                if (it.tileId == tileId) it.copy(accentArgb = accentArgb) else it
            },
        )
    }
}
