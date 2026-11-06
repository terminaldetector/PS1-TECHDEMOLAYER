# Metro fidelity checklist

Supersedes nothing: `OP02_METRO_FIDELITY_MATRIX.md` holds the per-feature
chronology table (WP8 / WP8.1 / 8.1 Update / Win10 Mobile). This file is the
functional checklist TZ v4 §3 asks for.

No Microsoft fonts, icons or image assets are redistributed. Segoe is not
bundled; the shell uses the platform sans-serif at Metro weights, which §3.1
permits as "a correct free substitute".

**No row has a screenshot.** The app has never run.

## Portrait — Windows Phone 8/8.1 (§3.1)

| Requirement | Status | Note |
|---|---|---|
| Black field, large light-weight type, minimal radii, strict spacing | `SOURCE_IMPLEMENTED` | `MetroTheme`, `displayLarge` at `FontWeight.Light` |
| Vertically scrolling start screen | `TESTED` | — |
| Tiles 1×1, 2×2, 4×2, 4×4 on an adaptive grid | `TESTED` | 16 packing tests, 2–12 columns |
| Reflow and repacking without overlaps | `TESTED` | first-fit proven; oversized tiles clamped |
| Pin / unpin / resize / move | `SOURCE_IMPLEMENTED` | unpinning never uninstalls |
| Folders and groups | `TESTED` | drag-onto-tile, one nesting level, dissolves under two members |
| Config export / import | `TESTED` | JSON round-trip |
| Full alphabetical app list | `SOURCE_IMPLEMENTED` | letter headers |
| Jump-to-letter | `TESTED` | whole alphabet, empty letters greyed not hidden |
| Search | `SOURCE_IMPLEMENTED` | prefix matches first |
| Wallpaper + solid accent mode | `TESTED` | — |
| **WP8.1 transparent mask** | `TESTED` | one photo, one crop/offset/zoom in whole-screen coordinates; continuity across tiles pinned by test; **not** duplicated per tile |
| Accent, transparency, contrast, animation intensity, themes | `SOURCE_IMPLEMENTED` | settings screen |
| Live tiles from real sources only | `SOURCE_IMPLEMENTED` | clock, date, battery, geometry; notification badges only with consent |
| No invented tile content | **by construction** | no weather tile exists; a badge is absent, never a fabricated zero |
| Tile editing, enter/exit animations | `SOURCE_IMPLEMENTED` | flip / slide / cycle |
| Start ↔ All Apps transition | `SOURCE_IMPLEMENTED` | horizontal pager, disabled in edit mode |
| Predictable swipes | `TESTED` | gesture contract, 12 tests |
| No permanent background render | **by construction** | composition-scoped; no background service exists |

## Landscape — Windows 8/8.1 Metro + Desktop (§3.2)

| Requirement | Status | Note |
|---|---|---|
| One Metro visual language, horizontal layout | `SOURCE_IMPLEMENTED` | — |
| Desktop with shortcuts and pinned apps | `SOURCE_IMPLEMENTED` | drag & drop on a cell grid |
| Start, taskbar, clock, indicators | `SOURCE_IMPLEMENTED` | — |
| Fast task switching | `PARTIAL` | pinned + shell-launched only; a true task list needs L2 |
| Optional full-screen Metro Start | `SOURCE_IMPLEMENTED` | Start panel; not the only route to apps |
| Mouse, keyboard, wheel, right click, drag & drop, shortcuts | `SOURCE_IMPLEMENTED` | real `isPrimaryPressed` / `isSecondaryPressed`; **no mouse has ever been attached** |
| Large targets for both finger and cursor | `SOURCE_IMPLEMENTED` | — |
| Scale independent of Android density | `TESTED` | hard boundary, `ShellScale` vs `DisplayAdaptationController` |
| Colour / wallpaper / tile / profile consistency with portrait | **by construction** | one collection, one workspace |

## System decoration (§3.3)

| Requirement | Status | Note |
|---|---|---|
| Metro top panel inside the shell | `SOURCE_IMPLEMENTED` | opens by tap, not swipe, so it never fights the system shade |
| Action Center | `SOURCE_IMPLEMENTED` | hands over to the real Quick Settings |
| Compact system panel + taskbar in Expanded Landscape | `SOURCE_IMPLEMENTED` | position and height configurable |
| Over third-party apps, only with consent | `BLOCKED` | overlay path not built; accessibility service not registered |
| No decorative panel passed off as system state | **by construction** | status values come from documented sources only |

## The honest gaps

| Item | Why |
|---|---|
| Screenshots | no device, no emulator |
| Golden / visual regression tests | needs an emulator |
| Per-tile photo picker | mode exists, picker not built |
| Overlay over other apps | `SYSTEM_ALERT_WINDOW` path not built |
| Lock screen | keyguard is L4; will not be attempted |
