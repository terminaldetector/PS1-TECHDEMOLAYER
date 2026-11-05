# Metro Optical Shell

A Windows Phone 8–style Android shell with **two functional environments** and
**three presentations**, plus an optical layer for use behind a lens.

| Presentation | Environment | Orientation | Look |
|---|---|---|---|
| `MOBILE_PORTRAIT` | Mobile | portrait | WP8 Metro tiles |
| `EXPANDED_PORTRAIT` | Expanded | portrait | **the same Metro tiles**, denser grid |
| `EXPANDED_LANDSCAPE` | Expanded | landscape | Metro desktop, taskbar, Start |

Portrait is always tiles. Rotation switches between the two Expanded
presentations; it never creates a second environment, a second set of apps or a
second workspace. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build

```bash
cd MetroOpticalShell
```

```bash
./gradlew assembleDebug
```

Produces `app/build/outputs/apk/debug/app-debug.apk`.

Requires JDK 17 and an Android SDK with `platforms;android-35` and
`build-tools;35.0.0`. Point Gradle at it with `local.properties`:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

```bash
./gradlew testDebugUnitTest
```

CI builds the same APK on every push and uploads it as a workflow artifact —
see [`.github/workflows/metro-optical-shell.yml`](../.github/workflows/metro-optical-shell.yml).

## Install and run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

It runs from its normal launcher icon. It needs **no root, no special
permissions and not the Home role**. Becoming the default launcher is optional
and off by default: the `HomeAlias` component ships disabled.

## What works

- WP8 Metro start screen: four tile sizes (1×1, 2×2, 4×2, 4×4), drag to
  reorder, tap to resize, unpin, groups, per-tile accent colours.
- Real app catalog from `PackageManager`/`LauncherApps`, alphabetical list with
  letter headers, search, and real `Intent` launches.
- Live tiles for clock, date, battery and measured window geometry — and
  nothing else, because that is the only data the app actually has.
- Manual Mobile ⇄ Expanded switch, always available.
- Expanded desktop: taskbar, Start panel, desktop shortcuts with drag & drop,
  real left/right mouse buttons, Esc to close Start.
- Optical layer: per-presentation profiles, independent per-edge safe-viewport
  insets, optical centre, scales, peripheral dimming, one magnifier region, a
  calibration grid with crosshair, and one-tap revert to the plain shell.
- Diagnostics screen reporting measured px, dpi, dp, insets and
  `WindowSizeClass`, plus the mathematical verdict on tablet geometry.
- Persistence through DataStore; layout survives rotation and process death.

86 unit tests cover the state machine, the tile packer, the display arithmetic,
the viewport maths, the repositories and the Tier 0 display bridge.

## What does not work, honestly

- **The shell has never run on a device.** No phone and no emulator were
  available. Everything above is "implemented and unit-tested", not
  "device-verified". See
  [METRO_OPTICAL_SHELL_OPERATION_01_REPORT.md](METRO_OPTICAL_SHELL_OPERATION_01_REPORT.md).
- **Third-party tablet layouts are unproven.** The headline question — whether
  `EXPANDED_PORTRAIT` can really give other apps a useful tablet layout — is
  still open. The protocol is in
  [docs/DEVICE_TEST_MATRIX.md](docs/DEVICE_TEST_MATRIX.md) and every row is
  empty.
- **The app cannot change system display density.** An ordinary APK has no shell
  privileges; `WRITE_SETTINGS` does not cover density. The app computes the exact
  ADB commands and verifies the result by re-measuring, but an operator runs
  them. See [docs/DISPLAY_ROLLBACK.md](docs/DISPLAY_ROLLBACK.md) and
  `tools/display-experiment/`.
- **Freeform windows are probed, not forced.** `setLaunchBounds()` is a hint that
  Android ignores where freeform is off. Where unsupported, apps launch
  full-screen and the desktop still works as a navigation shell. No fake windows
  are drawn.
- **The taskbar is not a task manager.** Without Launcher/Recents privileges the
  shell cannot enumerate running windows, so it shows what it pinned and what it
  launched, and labels it that way.
- No sound, no stereoscopy, no lens-distortion or chromatic-aberration
  correction. Those are extension points, left absent rather than imitated.

## The arithmetic that shapes the project

```
effectiveWidthDp = widthPx * 160 / densityDpi
```

Pixel width is fixed, so **more logical width requires lower density**, and
lower density makes everything physically smaller. Raising a DPI number does not
produce a tablet. The diagnostics screen states this trade in words, with your
device's real numbers, before asking for consent.
