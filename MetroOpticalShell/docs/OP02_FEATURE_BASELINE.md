# OP02 feature baseline — what Operation 01 actually left behind

TZ §1.3 is explicit: record what OP01 *really* implemented, and do not credit it
with features from the specification that are absent. This file is that audit,
made by reading the OP01 source, not the OP01 spec.

**Audited at:** commit `fe96bb0` on `claude/new-session-rlp3eq`, tree clean,
`assembleDebug` green, 86 unit tests passing, lint 0 errors.

**Branch note:** TZ §1.4 asks for `operation-02/metro-system-integration` "or
the existing accepted branching scheme". This session's assigned branch is
`claude/new-session-rlp3eq`, so OP02 continues there under that clause.

## Legend

`implemented` code exists and compiles · `tested` automated test covers it ·
`missing` not present · `blocked-by-privilege` needs L2+ · `needs-device` code
exists, behaviour unverified on hardware · `verified` seen working on a device

**Nothing in this project is `verified`.** No device has ever run it.

## Core / state

| Feature | Status | Note |
|---|---|---|
| Two environments, three presentations | `tested` | `Presentation` is derived, not stored; 13 tests |
| Rotation never changes environment | `tested` | pinned across repeated rotations |
| Manual Mobile ⇄ Expanded switch | `tested` | works in both orientations |
| `MOBILE` + landscape policy | `tested` | keeps tiles, offers Expanded, never silent |
| Persistence via DataStore | `tested` | JSON round-trips |
| Shared `ExpandedWorkspaceState` | `tested` | one object, both Expanded views |
| Process-death survival | `needs-device` | logic tested, real process death not exercised |

## Metro visual

| Feature | Status | Note |
|---|---|---|
| Vertical tile grid, 4 sizes | `tested` | 16 packing tests, 2–12 columns |
| Deterministic first-fit packing, clamping | `tested` | no overlap, no overflow |
| Drag to reorder, tap to resize, unpin | `implemented` | gestures unexercised |
| Groups (create/rename/delete/move) | `tested` | data rules tested; **no folder UI** |
| Per-tile accent | `tested` | — |
| Same surface for both portrait views | `tested` | one `MetroStartSurface` |
| Live tiles: clock, date, battery, geometry | `implemented` | composition-scoped, no service |
| App list, letter headers, search | `implemented` | needs a device to populate |
| **Transparent tiles / Start Background** | **`missing`** | OP01 had no wallpaper at all — **OP02 §3.2 P0** |
| **Visual presets (WP8 / WP8.1 / Expanded)** | **`missing`** | **OP02 §3.2** |
| **Live Folders with preview** | **`missing`** | groups exist in data only — **OP02 §3.4** |
| **Start ↔ App List horizontal swipe** | **`missing`** | OP01 used a header button only — **OP02 §3.5** |
| **Alphabet jump list** | **`missing`** | headers exist, no jump navigation — **OP02 §3.5** |
| **Flip/cycle tile animations** | **`missing`** | **OP02 §3.3** |
| Panorama/Pivot feel | `missing` | OP02 §3.6, P1 |

## Desktop / landscape

| Feature | Status | Note |
|---|---|---|
| Metro desktop, taskbar, Start panel | `implemented` | needs device |
| Desktop shortcuts, drag & drop | `implemented` | needs device |
| Real left/right mouse buttons | `implemented` | `isPrimaryPressed` / `isSecondaryPressed`; **no mouse was ever attached** |
| Esc closes Start | `implemented` | needs device |
| Freeform probe + full-screen fallback | `implemented` | matches Taskbar's signals |
| `OBSERVED` requires a human | `tested` | unreachable by code, by design |
| Recents = pinned + shell-launched only | `implemented` | honestly labelled |
| **`FOCUSED_FULLSCREEN` vs `MULTI_WINDOW_DESKTOP` as explicit modes** | **`missing`** | **OP02 §4 P0** |
| **`AppCompatibilityProfile`** | **`missing`** | **OP02 §5.1 P0** |
| **Landscape policy engine / app classes A–F** | **`missing`** | **OP02 §5.3 P0** |
| **`AppLayoutProbe`** | **`missing`** | **OP02 §5.2 P0** |

## Display adaptation

| Feature | Status | Note |
|---|---|---|
| Measured px/dpi/dp/insets/size class | `tested` | arithmetic tested; live read needs device |
| Tablet feasibility verdict | `tested` | 15 tests |
| Device-derived candidate densities | `tested` | not hard-coded |
| Tier 0 bridge refuses all writes | `tested` | cannot report success |
| Baseline recorded before any change | `tested` | incl. the "no override" case |
| Tier 1 ADB procedure + host tool | `implemented` | **never executed** |
| Tier 2 privileged adapter | `missing` | interface only, deliberate |
| **Transactional preview-before-apply** | **`missing`** | **OP02 §5.5 P0** |
| **Debounce / recursion guard** | **`missing`** | **OP02 §5.6** |

## Optical

| Feature | Status | Note |
|---|---|---|
| Per-presentation profiles | `tested` | 9 tests |
| Safe viewport, per-edge insets, centre | `tested` | system insets always honoured |
| Peripheral dimming | `implemented` | — |
| One magnifier region | `implemented` | shared `GraphicsLayer`, drawn twice |
| Calibration grid + crosshair | `implemented` | needs device |
| One-tap revert | `tested` | identity profile |

## System panels / gestures

| Feature | Status | Note |
|---|---|---|
| `<queries>` instead of `QUERY_ALL_PACKAGES` | `implemented` | — |
| `HomeAlias` disabled by default | `implemented` | never silently takes Home |
| **`MetroTopBar` / Action Center** | **`missing`** | **OP02 §6.1 P0** |
| **Notification bridge** | **`missing`** | OP02 §6.1 P1, consent-gated |
| **System panel adapter (QS/Recents)** | **`missing`** | **OP02 §6.2** |
| **`CapabilityGate`** | **`missing`** | **OP02 §2 — cross-cutting** |
| **Gesture insets coordinator** | **`missing`** | **OP02 §7 P0, blocking** |
| **Predictive back** | **`missing`** | **OP02 §7.2** |
| Gesture regression matrix | `missing` | needs device; §7 is a blocking criterion |

## Honest summary

OP01 delivered a **correct skeleton with a solid, tested core**: the
environment/presentation model, the tile packer, the display arithmetic and the
honesty machinery (tiers, `OBSERVED`, refusing bridges) are real and tested.

What it did **not** deliver, and OP02 §13 marks P0:
transparent tiles and Start Background, Live Folders, Start↔AppList swipe and
jump list, the two explicit landscape modes, app compatibility profiling, the
top panel/Action Center, the capability gate, and gesture-insets safety.

And the thing no amount of code closes: **nothing has run on a device.**
