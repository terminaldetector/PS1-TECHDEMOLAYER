# Architecture — two environments, three presentations

This is the invariant the whole project is organised around. It is not a
description of intent; it is enforced by the type system and pinned by tests.

```
                         METRO OPTICAL SHELL
                                  |
                             METRO CORE
                                  |
                +-----------------+------------------+
                |                                    |
              MOBILE                              EXPANDED
                |                                    |
          Portrait Metro                  Shared Expanded State
                                                     |
                                        +------------+-------------+
                                        |                          |
                                  Portrait Metro             Landscape Metro
                                   Tablet View                Desktop View
                                        |                          |
                                        +------------+-------------+
                                                     |
                                          App Runtime / Profiles

   Optical Calibration / Safe Viewport / Readability overlays ALL views.
```

## Why it cannot drift

| Rule | How it is enforced |
|---|---|
| Exactly two environments | `ShellEnvironment` has two entries. |
| Exactly three presentations | `Presentation` has three entries, and `ShellState.presentation` is a derived property, not stored state. Nothing can set a fourth. |
| Rotation never changes environment | `ShellStateMachine.reduce` handles `OrientationChanged` by copying `orientation` only. `ShellStateMachineTest` asserts it across repeated rotations. |
| Portrait is always tiles | `MOBILE_PORTRAIT` and `EXPANDED_PORTRAIT` both render the *same* composable, `MetroStartSurface`. There is no second tile widget and no alternative portrait surface to swap in. |
| One shared Expanded state | `ExpandedWorkspaceState` is a single object in a single DataStore key. Both Expanded presentations read it. There is no per-presentation copy. |
| Optics are a modifier | `SafeViewport` wraps the presentation in `MainActivity`, outside the `when (presentation)`. It is one layer over all three, not a fourth screen. |

## Module map

```
core/        ShellModel, ShellStateMachine, OrientationPolicy,
             ShellViewModel, CapabilityReport
metro/       the tile system: model, packer, grid, tile views, app list,
             the shared MetroStartSurface, edit controls, live tile sources
mobile/      MOBILE_PORTRAIT wrapper
expanded/    ExpandedWorkspaceState + the two Expanded presentations
desktop/     Taskbar, StartMenu, DesktopWorkspace
runtime/     InstalledAppsRepository, StandardAppLauncher, FreeformLaunchAdapter
display/     DisplayMath, DisplayMetricsReader, SystemDisplayBridge,
             DisplayAdaptationController, DisplayExperimentRepository
optical/     OpticalProfile, SafeViewportMath, SafeViewport, CalibrationScreen
data/        DataStore-backed repositories
diagnostics/ DeviceCapabilityScreen
ui/          MetroTheme, SettingsScreen
```

## The hard boundary: shell scale vs system density

TZ 4.1 requires these never to be the same knob, and they are not:

| | Changes | Scope | Lives in |
|---|---|---|---|
| Shell scale | tile size, type size, padding | this APK only | `AppearancePreferences`, `OpticalProfile`, `MetroTheme` |
| System density | logical dp of the whole device | every app | `DisplayAdaptationController` → `SystemDisplayBridge` |

They move in **opposite directions**. Raising a DPI number does not create a
tablet. Buying logical width at a fixed pixel width requires *lowering* density,
which makes everything physically smaller. `DisplayMath` computes that trade and
`DeviceCapabilityScreen` states it in words before asking for consent.

## Tile layout is derived, never stored

Persisted per tile: `order` and `size`. **Not** `(col, row)`.

The same collection has to render at 4, 6, 8 or 10 columns depending on
presentation and shell scale. Absolute coordinates would be wrong at every
width but one, so `TilePackingAlgorithm.pack(tiles, columns)` derives the
layout. Consequences:

- rotating the device cannot scramble the start screen;
- a drag is one integer edit;
- packing is pure, so it is fully unit-tested;
- oversized tiles are **clamped** to the column count, never clipped.

## Package visibility

A launcher legitimately needs to enumerate launchable activities. The manifest
declares a narrow `<queries>` element for `ACTION_MAIN` + `CATEGORY_LAUNCHER`
rather than requesting `QUERY_ALL_PACKAGES`, which is a restricted permission.
`InstalledAppsRepository` prefers `LauncherApps` and falls back to
`queryIntentActivities`.

## Capability tiers

| Tier | Meaning | Ships enabled |
|---|---|---|
| 0 | read-only; metrics and diagnostics; no system change | **yes** |
| 1 | density change performed by an operator over ADB; the app computes the commands and verifies the result by re-measuring | procedure only |
| 2 | privileged in-process adapter (Shizuku / ADB bridge / platform signature) | **no implementation** |

`PrivilegedDisplayAdapter` exists as an interface so the UI is already
tier-agnostic. No hidden APIs, reflection into `IWindowManager`, or root hooks
are present in any shipped path.

## Three words that are never conflated

- `UNSUPPORTED` — the device reports no support.
- `REQUESTED` — we asked. We do not know what happened.
- `OBSERVED` — a human saw it work and recorded it.

`FreeformStatus` documents the verification method for each value in its own
KDoc. The shell cannot reach `OBSERVED` by itself, because without
Launcher/Recents privileges it cannot see other apps' windows.
