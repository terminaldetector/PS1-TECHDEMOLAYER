# Landscape compatibility

## 1. The two landscape styles (TZ v4 §4.1)

Both live inside ONE `EXPANDED_LANDSCAPE` presentation and share one workspace.

| Style | What it is | Availability |
|---|---|---|
| `FOCUSED_FULLSCREEN` | one app across the usable landscape area | **always** |
| `MULTI_WINDOW_DESKTOP` | real system tasks in split / freeform | only where the system provides it |

Switching styles does not reset pinned apps, tiles or workspace state.

## 2. How a launch is decided (TZ v4 §4.3)

Every surface — tile, all-apps, desktop shortcut, taskbar, search, notification,
deep shortcut, folder — goes through `AppLaunchOrchestrator.launch()`. There is
no second path; FIX-03 was precisely that there had been one.

The seven steps, in order:

1. resolve package, Activity and the current configuration;
2. fetch the `AppCompatibilityProfile` and the real window measurements;
3. `LandscapePolicyEngine.decide()` chooses fullscreen / split / freeform /
   compatibility;
4. check the capability for **this operation** — `SET_WINDOWING_MODE` for a
   windowed launch, `LAUNCH_APP` otherwise — not a general privilege level;
5. perform the real request through the public or privileged adapter;
6. record `REQUESTED`; only a later measurement may say `OBSERVED`;
7. on refusal fall back to a working full-screen start, changing no user data.

### Three claims the engine will not make

- **It never claims a window it cannot get.** A device reporting freeform is
  still not enough, because `setLaunchWindowingMode` is a hidden API needing
  L2 — there is a test for exactly that.
- **It never forces another app's orientation.** There is no API for a normal
  app to call `setRequestedOrientation` on someone else's Activity, so a
  portrait-locked app gets the system's compatibility path, not a rotation
  fight.
- **It never promises a tablet layout from width alone.** Width is necessary,
  not sufficient.

## 3. App classes (TZ v4 §5.1)

| Class | Meaning | Policy |
|---|---|---|
| A | adaptive | give real width; expect a genuine layout change |
| B | resizable but phone-shaped | honest fullscreen: larger, not different |
| C | portrait-locked | system letterbox / portrait canvas; no forcing |
| D | genuinely unstable | stable configuration, defect logged |
| E | protected pipeline (game, DRM, camera) | do not interfere at all |
| F | OEM-specific | version-gated workaround, switchable off |

### FIX-05: recreation is not a defect

An `Activity` being recreated on a configuration change is **documented Android
behaviour** and most apps handle it correctly. The previous implementation
classified any such app as class D, which would have mislabelled the majority of
well-behaved apps.

A real defect now requires one of: `stateLost`, `crashed`, `brokenGeometry`,
`inputLost`, `restartLoop`. `recreatedOnRotate` is recorded but carries no
verdict on its own. Two tests pin this.

## 4. Tablet layout and density (TZ v4 §5.3)

Three things kept separate:

| | What it controls | Who can change it |
|---|---|---|
| system `densityDpi` | logical size for every app | L2/L3/platform only |
| `windowWidthDp` / `smallestScreenWidthDp` | this window's adaptive behaviour | measured, never inferred from orientation |
| readability | the shell's own text and target sizes | us, inside our APK |

The arithmetic that governs the whole question:

```
effectiveWidthDp = widthPx * 160 / densityDpi
```

Pixel width is fixed, so more logical width requires **lower** density, which
shrinks everything proportionally. For a 1080×2400 @ 440dpi panel: 600dp needs
288dpi (~65% of current physical size) and is reachable; 840dp needs 205dpi and
is **not**, without unacceptable readability loss.

**600dp is not a sufficient condition.** As §5.3 warns, Android 16+ orientation
and resizability rules depend on target SDK, the display's *smallest* width,
documented exceptions and the OEM's implementation — so reaching 600dp of window
width does not by itself produce a tablet layout. This must be measured per app
per device.

## 5. Results — `NOT COLLECTED`

No device or emulator has ever been available. `DEVICE_TEST_MATRIX.csv` holds
the rows; every one is `NOT_RUN`.

The app corpus §12 requires (minimum 8 classes): adaptive file manager, browser,
office/text editor, messenger, video, Winlator, Termux, and a game/camera/DRM
case. None selected, because the device decides what is installed.

**The success criterion, restated:** a real structural change — a new pane, a
master/detail split, a navigation rail. Smaller text or a stretched Activity does
not count, and that distinction is the entire point of the exercise.
