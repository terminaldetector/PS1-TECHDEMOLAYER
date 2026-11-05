# Metro Optical Shell — Operation 01 report

**Date:** 5 November 2026
**Build environment:** Linux container, JDK 21 / Gradle 8.14.3, Android SDK
platform 35 + build-tools 35.0.0, no device and no emulator.
**Branch:** `claude/new-session-rlp3eq`
**Location:** `MetroOpticalShell/` (the PlayStation 1 demo at the repository
root is a separate project and was not modified).

---

## 1. Headline answer

The shell is **built, compiling and unit-tested**. It has **never run on a
device.**

The project's main experimental question — *can `EXPANDED_PORTRAIT` really be
turned into a useful tablet layout for third-party apps, and then reused in
`EXPANDED_LANDSCAPE`?* — **remains open.** It cannot be answered without
hardware, and this report does not pretend otherwise.

What *was* settled is the arithmetic that governs the answer, which turns out to
be the more useful result:

```
effectiveWidthDp = widthPx * 160 / densityDpi
```

Pixel width is fixed. So gaining logical width requires **lowering** density,
and that shrinks everything on screen in exact proportion. For a typical
1080×2400 phone at 440 dpi:

| Target | Required density | Everything becomes | Verdict |
|---|---|---|---|
| current | 440 dpi | 100% | 392 dp, `COMPACT` |
| 600 dp (`MEDIUM`) | 288 dpi | ~65% of current size | reachable, inside the guard |
| 840 dp (`EXPANDED`) | 205 dpi | ~47% of current size | only below the readability guard |

These numbers are computed by `DisplayMath` and pinned by `DisplayMathTest`. The
practical conclusion: **600 dp is reachable on a modern 1080p phone; 840 dp is
not, without unacceptable readability loss.** So a two-pane tablet layout is
likely out of reach on this class of device, while a `MEDIUM`-class layout is
plausibly within it. Which apps actually respond is precisely what the device
matrix is for.

---

## 2. Status by subsystem

| Subsystem | Status | Evidence |
|---|---|---|
| Gradle/Android project, pinned versions | `TESTED` | `assembleDebug` succeeds; AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01, no dynamic versions |
| Debug APK | `TESTED` | `app/build/outputs/apk/debug/app-debug.apk`, ~11.3 MB; also built green on CI and uploaded as a workflow artifact |
| Two environments / three presentations | `TESTED` | `ShellStateMachineTest`, 13 tests |
| Metro tile core (4 sizes, packing) | `TESTED` | `TilePackingAlgorithmTest`, 16 tests |
| Tile drag / resize / unpin / groups | `IMPLEMENTED` | `MetroTileGrid`, `TileEditController`, `TileCollectionTest` covers the data rules |
| Real app catalog + Intent launch | `IMPLEMENTED` | `InstalledAppsRepository`, `StandardAppLauncher`; needs a device to exercise |
| Live tiles (clock/date/battery/geometry) | `IMPLEMENTED` | `LiveTileData`; composition-scoped, no service |
| Expanded portrait = same tiles | `TESTED` | one `MetroStartSurface` used by both portrait presentations; `A05` by construction |
| Expanded landscape desktop | `IMPLEMENTED` | `DesktopMetroScreen`, `Taskbar`, `StartMenu`, `DesktopWorkspace` |
| Shared Expanded workspace | `TESTED` | `ExpandedWorkspaceTest`, 9 tests |
| Mouse / keyboard | `IMPLEMENTED` | real `isPrimaryPressed`/`isSecondaryPressed`, Esc handling; unverified without hardware |
| Persistence (DataStore, JSON export) | `TESTED` | `TileCollectionTest`, `ExpandedWorkspaceTest` round-trips |
| Display diagnostics (px/dpi/dp/insets/class) | `TESTED` | `DisplayMathTest`, 15 tests; `DisplayMetricsReader` needs a device |
| Display bridge Tier 0 | `TESTED` | `DisplayBridgeTest`, 6 tests; refuses every write by construction |
| Display bridge Tier 1 (ADB) | `PARTIAL` | procedure + host tool written and syntax-checked; **never executed** |
| Display bridge Tier 2 (privileged) | `NOT_STARTED` | interface only, by design |
| Third-party tablet layout | `BLOCKED` | no device; `docs/DEVICE_TEST_MATRIX.md` protocol written, all rows empty |
| Optical layer (viewport, profiles, magnifier) | `TESTED` | `SafeViewportMathTest`, 9 tests; `CalibrationScreen` implemented |
| Freeform probe + fallback | `IMPLEMENTED` | `FreeformLaunchAdapter`; `OBSERVED` unreachable without a human |
| HyperOS behaviour | `BLOCKED` | `docs/HYPEROS_LIMITATIONS.md` separates platform facts from unverified OEM claims |

**86 unit tests, 0 failures. Lint: 0 errors, 30 warnings.**

All 30 remaining lint warnings are `GradleDependency` / `AndroidGradlePluginVersion`
("a newer version is available"). They are expected and deliberate: TZ OP01-A.3
requires versions to be pinned to what the environment actually has, and forbids
dynamic versions. Every other lint finding was fixed rather than suppressed:
the redundant `-anydpi-v26` qualifier and unused round icon (minSdk is 26), a
missing `<monochrome>` layer for Android 13+ themed icons, a dead
`SDK_INT >= N` check, and a per-second `Long` tick that was boxing on every
tick (now `mutableLongStateOf`).

```
DisplayBridgeTest           6
DisplayMathTest            15
ExpandedWorkspaceTest       9
OrientationPolicyTest       6
SafeViewportMathTest        9
ShellStateMachineTest      13
TileCollectionTest         12
TilePackingAlgorithmTest   16
```

Instrumented tests were **not** written: with no emulator or device they could
not be run, and committing unrun instrumented tests would be the kind of
unverified claim this project is built to avoid.

---

## 3. Acceptance criteria

`TESTED` below means automated tests pass. It is deliberately a weaker claim
than `DEVICE_VERIFIED`, which nothing in this operation has earned.

| Code | Check | Result | Note |
|---|---|---|---|
| `A01` | APK builds | **TESTED** | clean `assembleDebug` locally **and** a green GitHub Actions run on a fresh runner, APK uploaded as an artifact |
| `A02` | Mobile Portrait = WP8 Metro | `IMPLEMENTED` | needs a screenshot from a device |
| `A03` | Four editable tile sizes | **TESTED** | packing/clamping/no-overlap proven for 2–12 columns |
| `A04` | Real apps listed and launched | `IMPLEMENTED` | real APIs wired; no device to run them |
| `A05` | Expanded Portrait = same tiles | **TESTED** | structurally one composable, one collection |
| `A06` | Expanded Landscape desktop | `IMPLEMENTED` | needs a device with a mouse |
| `A07` | Manual mode switching | **TESTED** | both directions, both orientations |
| `A08` | Persistence | `PARTIAL` | JSON round-trips tested; real process death untested |
| `A09` | dp diagnostics | **TESTED** | arithmetic tested; live read needs a device |
| `A10` | System display bridge | **TESTED (Tier 0)** / `PARTIAL` (Tier 1) | Tier 0 cannot report success; Tier 1 never executed |
| `A11` | Third-party tablet layout | **BLOCKED** | no device. Not claimed. |
| `A12` | Safe rollback | `PARTIAL` | logic tested incl. the no-override case; never exercised on hardware |
| `A13` | Optical mode | **TESTED** | profiles per presentation, tiles never displaced |
| `A14` | Freeform | `IMPLEMENTED` | probe + fallback; no false force-window claim |
| `A15` | Report | **done** | this file |

Baseline target was `A01–A09`, `A13`, `A15`. Of those, `A01`, `A03`, `A05`,
`A07`, `A09` and `A13` are automated-tested; `A02`, `A04`, `A06` are implemented
but need a device; `A08` is partial. `A10–A12` are honestly split between code,
lab readiness and actual verification, as the TZ requires.

---

## 4. Decisions made without asking

1. **Tile layout is derived from an order, not stored coordinates.** The same
   collection must render at 4–10 columns; absolute positions would be wrong at
   every width but one. This is what makes rotation safe.
2. **`MOBILE` + `LANDSCAPE` resolves to `MOBILE_PORTRAIT`** with a dismissible
   "switch to Expanded?" bar. Keeps three presentations exactly, keeps tiles as
   tiles, and never switches environment silently.
3. **One `MetroStartSurface` for both portrait presentations.** Makes "Expanded
   portrait is still Metro tiles" structurally true rather than a promise — there
   is no second surface to swap in.
4. **kotlinx.serialization** was added so the tile/profile JSON is pure-JVM
   testable without Robolectric. Room was not: the schema is a flat list.
5. **The readability guard is a fraction (62.5%) of the device's own stock
   density**, not a hard dpi number, because a 1080p and a 1440p panel differ.
6. **`FreeformStatus.OBSERVED` can only be set by a human** through the
   diagnostics screen. The shell cannot see other apps' windows, so it must not
   claim to.
7. **The magnifier is implemented for real** (one region, via a recorded
   `GraphicsLayer` drawn twice) since the TZ puts it in Operation 01 scope.
   Stereoscopy and distortion/aberration correction are absent, not faked.
8. **No background service.** The clock and live tiles are composition-scoped,
   which also sidesteps MIUI's background restrictions.
9. **`<queries>` instead of `QUERY_ALL_PACKAGES`.** A launcher's legitimate need
   is served by the narrow declaration; the restricted permission is not
   requested.
10. **`HomeAlias` ships `enabled="false"`.** The shell never takes over Home
    without an explicit user action.

---

## 5. Environment obstacles and how they were handled

| Obstacle | Resolution |
|---|---|
| `dl.google.com` blocked by the sandbox network policy (403 on CONNECT) — no Android SDK, no AGP, no AndroidX | A dedicated personal cloud environment was proposed and approved, allowing `dl.google.com` and `maven.google.com`, and this session's network moved onto it |
| Android SDK licence must be accepted | Asked the user; they approved, and the SDK was then installed |
| Maven Central intermittently returned HTTP 429 through the proxy | A local-only Gradle init script points at Google's Maven Central mirror. **Not committed** — `settings.gradle.kts` stays standard `google()` + `mavenCentral()` so CI and other machines are unaffected |
| `gradle wrapper` failed because evaluating the build resolved AGP during the 429 window | Wrapper generated in a throwaway project and copied in |
| No device, no emulator | Instrumented tests not written; every device-dependent claim marked `NOT DEVICE VERIFIED` |

---

## 6. Honest list of what is unverified

- The app has never been installed or launched. No screenshot exists.
- No third-party app has been observed changing layout. `A11` is **not** met.
- No density change has ever been applied or rolled back on hardware.
- No freeform launch has been attempted on a device.
- No mouse or physical keyboard has touched the desktop presentation.
- HyperOS-specific behaviour is documented from platform docs and reputation,
  not from observation.
- Startup time, frame timings and memory footprint are unmeasured. No
  performance claim is made.
- Drag-and-drop feel, the drop-index rule and the magnifier's appearance are
  untested against a human's judgement.
- Instrumented and Compose UI tests do not exist.

---

## 7. Next operation

1. **Run it.** Install on the Redmi/HyperOS target. Screenshot all three
   presentations. That alone converts `A02`, `A04`, `A06` from implemented to
   verified.
2. **Fill `docs/DEVICE_TEST_MATRIX.md`.** Use
   `tools/display-experiment/display-experiment.sh status` and `candidates`
   first — if the verdict is `NOT_REACHABLE`, record that and stop; it is a real
   answer.
3. **Run the Tier 1 experiment** on 3–5 real apps, with screenshots, then
   restore and confirm. Settle `A11` either way.
4. **Verify rollback**, including the no-prior-override path.
5. **Record a freeform observation** through the diagnostics screen.
6. **Add instrumented tests** once an emulator is available: rotation state
   retention, process death, tile drag, launching a real app.
7. **Measure performance**: startup, frame timing on tile drag, catalog load
   with 200+ apps.
8. Only then consider Tier 2 (Shizuku) — and only if the device matrix shows
   that a reachable density actually buys a real tablet layout. If it does not,
   the right conclusion is that this device class cannot do it, and the shell
   should stop promising it.
