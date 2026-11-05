# Device test matrix

**Status of this file: the protocol is written; no row is filled in.**

No physical Android device and no emulator were available in the build
environment for Operation 01. Every third-party tablet-layout row below is
therefore `NOT DEVICE VERIFIED`. Filling them in is the first task of the next
operation, and the TZ's headline question — *can `EXPANDED_PORTRAIT` really
become a useful tablet layout for third-party apps?* — stays **open** until they
are.

Per TZ 4.4 / A11, the only status that may ever be written as a success is one
that was actually observed on hardware.

## Status vocabulary

| Status | Means |
|---|---|
| `SHELL_ONLY` | Our own layout adapts. Says nothing about other apps. |
| `SYSTEM_CONFIGURATION_APPLIED` | Density changed and the shell re-measured and confirmed it. |
| `THIRD_PARTY_TABLET_LAYOUT_VERIFIED` | A specific third-party app was seen to change layout. **Requires a screenshot.** |

## Device under test

| Field | Value |
|---|---|
| Manufacturer / model | _not filled_ |
| Android release / API level | _not filled_ |
| ROM (HyperOS/MIUI version) | _not filled_ |
| Panel px (w x h) | _not filled_ |
| Stock densityDpi | _not filled_ |
| Stock width dp | _not filled_ |
| WindowSizeClass at stock | _not filled_ |
| Readability guard (62.5% of stock) | _not filled_ |
| Verdict from DisplayMath.assess | _not filled_ |

Read every value from the shell's own diagnostics screen (`ⓘ` in the header),
not from a spec sheet — `wm size` and `wm density` are the ground truth.

## Procedure

1. Install the debug APK. Launch it from its normal icon; do **not** grant it
   the Home role yet. Confirm the shell starts with no privileges.
2. Open diagnostics. Record every field in the table above.
3. Note the mathematical verdict **before** changing anything. If it says
   `NOT_REACHABLE`, record that and stop: the honest answer for that device is
   that there is no useful tablet width to be had.
4. Pick 3–5 **actually installed** apps of different kinds (see below). For each,
   record the baseline row.
5. Apply the density from the diagnostics screen using the ADB procedure in
   [DISPLAY_ROLLBACK.md](DISPLAY_ROLLBACK.md). Confirm the shell reports
   `OBSERVED`.
6. Re-open each app. Record the expanded row. **A screenshot is required** for
   any `Layout changed? = yes`.
7. Rotate each app. Record whether the layout survives or the activity is
   recreated.
8. Restore with the rollback procedure. Confirm the shell reports the original
   numbers.

## App selection (TZ 4.4)

Pick one of each: a file manager, a browser, an editor or terminal, and at
least one app with a plausible two-pane layout. Use what is really on the
device; do not substitute a mock.

## Baseline / expanded rows

| # | App (package) | Kind | Baseline window dp | Expanded window dp | Layout changed? | New functionality | Text/touch usable? | Rotation behaviour | Restored? | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | _not filled_ | file manager | | | | | | | | `NOT DEVICE VERIFIED` |
| 2 | _not filled_ | browser | | | | | | | | `NOT DEVICE VERIFIED` |
| 3 | _not filled_ | editor/terminal | | | | | | | | `NOT DEVICE VERIFIED` |
| 4 | _not filled_ | two-pane candidate | | | | | | | | `NOT DEVICE VERIFIED` |
| 5 | _not filled_ | | | | | | | | | `NOT DEVICE VERIFIED` |

"Layout changed?" means a genuinely different arrangement — a navigation rail, a
side pane, a master/detail split. **Smaller versions of the same phone layout do
not count.** That distinction is the whole point of the experiment: lowering
density always makes things smaller, and mistaking that for a tablet layout is
the error the TZ is written to prevent.

## Shell-side checks (do not need the density experiment)

| Check | How | Result |
|---|---|---|
| `A02` Mobile portrait is WP8 Metro | launch, screenshot | `NOT DEVICE VERIFIED` |
| `A03` Four tile sizes, drag, resize | edit mode, cycle each tile | `NOT DEVICE VERIFIED` |
| `A04` Real apps listed and launched | app list, tap | `NOT DEVICE VERIFIED` |
| `A05` Expanded portrait is the same tiles | switch environment, compare | `NOT DEVICE VERIFIED` |
| `A06` Expanded landscape desktop | rotate in Expanded; attach mouse+keyboard | `NOT DEVICE VERIFIED` |
| `A07` Manual switching | toggle both ways in both orientations | `NOT DEVICE VERIFIED` |
| `A08` Persistence | rearrange, force-stop, relaunch | `NOT DEVICE VERIFIED` |
| `A13` Optical profiles | calibrate each presentation, then revert | `NOT DEVICE VERIFIED` |
| `A14` Freeform probe | desktop → right-click → open in window; record observation | `NOT DEVICE VERIFIED` |

The logic behind `A03`, `A05`, `A07`, `A08` and `A09` is covered by the 86
passing unit tests, which is a different and weaker claim than device
verification. See [../METRO_OPTICAL_SHELL_OPERATION_01_REPORT.md](../METRO_OPTICAL_SHELL_OPERATION_01_REPORT.md).

## Freeform observation

| Field | Value |
|---|---|
| `FEATURE_FREEFORM_WINDOW_MANAGEMENT` | _not filled_ |
| `enable_freeform_support` global | _not filled_ |
| Status after launch attempt | _not filled_ |
| Did a window actually appear? | _not filled_ |

Record the answer through the diagnostics screen's two buttons. The shell cannot
determine this by itself — it cannot see other apps' windows without
Launcher/Recents privileges.
