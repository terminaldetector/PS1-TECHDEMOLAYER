# Metro Optical Shell — Operation 02 implementation report

**Date:** 5 November 2026
**Base:** OP01 at `fe96bb0`, tree clean, 86 tests green
**Branch:** `claude/new-session-rlp3eq` (TZ §1.4's "existing accepted branching scheme")
**Environment:** Linux container, JDK 21 / Gradle 8.14.3, Android SDK 35. **No device, no emulator.**

---

## 1. What this operation actually achieved

OP02 is largely a **device operation**. Its blocking acceptance criterion (§7,
gestures) and most of its P0 verification (§11 hardware matrix) cannot be touched
without a Xiaomi/HyperOS phone. There was none.

So the work went where it could be real: **settling the platform questions from
primary sources**, and **building the parts that are provable without hardware**.

The most valuable output is not code. It is that §1.1's capability questions are
now answered **with verified evidence** instead of assumption — including two
answers that constrain the whole project:

1. **We can never be the Recents/gesture provider.** AOSP's
   `TouchInteractionService` is gated on `STATUS_BAR_SERVICE`, and the Quickstep
   manifest also demands `MONITOR_INPUT` and `ALLOW_SLIPPERY_TOUCHES` — the two
   permissions that let a gesture be observed and stolen from another app's
   surface. No user grant, no Shizuku bridge and no root changes this. It is L4.
2. **Nobody gets a true window list unprivileged.** Smart Dock — the most
   mature implementation of this feature set — reaches `IActivityManager`
   through a **Shizuku** binder for the real task list, uses **root**
   (`am task resize`) to resize a task, and falls back to
   `UsageStatsManager` recency when unprivileged. Its fallback is an inference,
   not a window list.

Both were verified by cloning the sources at the exact revisions the spec cites
and reading them. **All four cited blob SHAs matched the spec exactly**, which
is recorded in `OP02_UPSTREAM_RESEARCH.md`.

A third finding validates OP01: **Taskbar gates freeform on exactly the two
signals our OP01 probe already reads** (`FEATURE_FREEFORM_WINDOW_MANAGEMENT` and
`Settings.Global enable_freeform_support`). Its README's "freeform without root"
means *detection*, not *enabling* — precisely as TZ §8.4 warned.

---

## 2. Code delivered

**214 unit tests, 0 failures** (86 → 135 → 214, +128 over OP01). Clean `assembleDebug`, lint 0 errors, APK ~11.3 MB.

### `CapabilityGate` (§2) — `IMPLEMENTED`, `TESTED` (15 tests)

The enforcement mechanism §2 asks for. Every capability carries a
`PrivilegeTier` and its primary-source evidence. Two properties are enforced by
the type rather than by discipline:

- a capability above the held tier **cannot** return `AVAILABLE`;
- an L4 capability returns `IMPOSSIBLE_AT_APP_TIER` **whatever** is granted —
  there is a test asserting that root does not make us the Recents provider.

A test also asserts that every L2/L3 tier claim cites evidence, so the table
cannot grow an unsourced assertion.

### WP8.1 transparent tiles and Start Background (§3.2, P0) — `IMPLEMENTED`, `TESTED` (16 tests)

The headline visual item, and OP01 had no wallpaper at all.

Built from the two Microsoft articles, which settle the mechanism: **one** shared
Start Background with **one** crop/zoom/pan transform, revealed where a tile's
own asset is transparent. So the implementation samples one bitmap in **one
shared canvas coordinate space**, translating it per tile and clipping to the
tile's bounds.

That is what the tests pin, and the invariant that matters is **continuity**: an
adjacent tile's source rectangle must begin exactly where its neighbour's ends,
horizontally and vertically, with no seam. Drawing the wallpaper independently
inside each tile — the mistake §3.2 calls out — would repeat or stretch it per
cell and destroy the effect. A further test proves the only per-tile difference
is a translation, which is how "no bitmap allocation per tile" (§3.2) is met.

All five modes exist, with `TRANSLUCENT_ACCENT` kept **explicitly labelled as
ours** so it is never mistaken for the historical effect. Wallpaper decoding is
off the main thread, subsampled to ≤2048 px, and keyed on the URI so scrolling
never re-decodes.

### Landscape compatibility engine (§4, §5, P0) — `IMPLEMENTED`, `TESTED` (18 tests)

`AppCompatibilityProfile` exactly as §5.1 specifies, plus the A–F classes and
the two equal landscape modes.

Three rules are enforced and tested:

- **Never claim a window it cannot get.** `MULTI_WINDOW_DESKTOP` is chosen only
  when the gate says windowing is `AVAILABLE`; otherwise it falls back to
  `FOCUSED_FULLSCREEN` and says why. A test proves that a device reporting
  freeform is *still* not enough without an L2 bridge, because
  `setLaunchWindowingMode` is hidden.
- **Never force another app's orientation.** Class C gets the system's
  letterbox path, not a rotation fight; class E is left entirely alone.
- **Never promise a tablet layout.** Width is necessary, not sufficient.

`LayoutObservation.layoutChanged` is a **nullable** Boolean so that "unknown"
cannot collapse into "no" — §11.4 requires `observed_layout = unknown` unless
proved by a capture. `classify()` returns `null` when evidence is insufficient,
and a layout change **without evidence does not earn class A**. Tests assert
both.

---

## 3. Second pass: the rest of the implementable spec

Everything below was built after the first pass, in one run.

### Gestures (§7, P0 — the blocking criterion) — `IMPLEMENTED`, `TESTED` (12 tests)

`GesturePriorityContract` encodes the §7.2 priority order as arithmetic:

```
system mandatory > accessibility/security > system bars >
shell navigation > tile interactions > decorative
```

What the tests pin, and why each matters:

- **Nothing is claimed while the IME is up.** The user needs Back more than the
  shell needs a swipe.
- **Claims never reach into the mandatory strip.** Ask for the whole screen and
  the result is clipped above the home/recents region.
- **The total claim never exceeds the platform's 200dp per-edge cap.** Rather
  than hand Android rects it would silently drop, the budget is allocated
  explicitly, highest priority first — a test proves a `DECORATIVE` request
  loses to a `SHELL_NAVIGATION` one when the budget is tight.
- **Only tile surfaces may claim a horizontal swipe.** The desktop opens Start
  with a button, so claiming edge swipes there would fight Back for nothing.
- **An OEM downgrade is reported, not masked.** `NavigationStatus` distinguishes
  "this device is on buttons anyway" from "we took Home and gestures vanished";
  the latter is `isBlocking` and its message tells the user how to revert.

Exclusion rects are installed in a `DisposableEffect` and cleared on dispose, so
a stale exclusion cannot outlive its screen and quietly keep stealing Back.
Predictive back uses the AndroidX `BackHandler`, and is disabled whenever the
shell has nothing to consume so Back always falls through to the system.

**Lint caught a real bug here**: `setSystemGestureExclusionRects` is API 29 but
`minSdk` is 26, so this would have crashed on Android 8–9. Now guarded.

### Start ↔ All Apps swipe and jump list (§3.5, P0) — `IMPLEMENTED`, `TESTED` (7 tests)

The classic horizontal pager, with paging disabled in edit mode so a tile drag
cannot also flip the page. The jump list shows the **whole** alphabet with empty
letters greyed rather than hidden, as WP did, so the grid never reflows as apps
are installed; `#` sorts last.

### Live Folders (§3.4) — `IMPLEMENTED`, `TESTED` (12 tests)

Drag a tile squarely onto another to create a folder — the inner-60% test is
what separates "onto" from "beside", so an ordinary reorder near a tile cannot
silently create one. Nesting is capped at one level, and removing the
second-to-last member **dissolves** the folder rather than leaving a one-item
container. Dissolving never loses a tile.

### Tile animation (§3.3) — `IMPLEMENTED`, `TESTED` (9 tests)

Flip/slide/cycle, with every condition a veto: tile off screen, screen off,
shell backgrounded, edit mode, battery saver, reduced motion. Stagger is derived
from the tile id so it is stable across restarts — a random offset would make
the start screen look different every launch.

### Top bar and Action Center (§6.1, P0) — `IMPLEMENTED`

The shell's own strip, inside its own window. It opens by **tap, not swipe**, so
it never competes with the system's shade gesture (§6.1 warns that two rival
swipes are worse than one panel). Quick actions **hand over** to Android's own
panels instead of rendering toggles the shell cannot operate. A notification
badge is shown only when access is actually granted: absent, not zero.

### Notification bridge (§6.1, P1) — `IMPLEMENTED`

Consent-gated `NotificationListenerService`. It keeps only package and count —
titles are deliberately discarded — and on disconnect the cache is **cleared**,
so a stale badge cannot outlive the permission.

### Task source honesty (§4.3, §6.3) — `IMPLEMENTED`, `TESTED` (10 tests)

`TaskSource` keeps the five notions §4.3 forbids conflating distinct, and the
accuracy label travels with the data so the UI physically cannot present usage
statistics as a window list. An unprivileged shell can only ever reach
`SHELL_LAUNCHED`; consent lifts it to `OBSERVED_RECENCY`, which is still not
live state; only an L2 bridge reaches a true task list. Where there is no true
list, Recents **defers to the system's own overview**.

### Display profile manager (§5.5) — `IMPLEMENTED`, `TESTED` (8 tests)

The preview-before-apply step OP01 lacked. It states the resulting dp, the width
class, the shrink percentage, the effect on a 48dp control, and that the change
is system-wide. A profile that breaches the readability guard **or** shrinks
touch targets past the minimum reports `safeToOffer = false`. `verify()` compares
against a fresh measurement and complains on mismatch.

### Debounce and recursion guard (§5.6) — `IMPLEMENTED`, `TESTED` (7 tests)

A change must persist before it counts, so a phone held near 45° cannot thrash
the shell — a test feeds 40 alternating readings and asserts not one change. And
applying a profile suppresses input entirely, because a density change itself
triggers configuration changes that would otherwise recurse.

### Crash-loop guard (§12) — `IMPLEMENTED`, `TESTED` (7 tests)

Two strikes disables an **optional** feature of ours and says why; a clean run
clears the streak; essential features are never auto-disabled; the user can
always switch one back on. It never touches system state.

### App layout probe (§5.2) — `IMPLEMENTED`, `TESTED` (7 tests)

`recordLaunch` records what the shell measured and leaves `layoutChanged`
**null**, because the shell cannot see inside another app's window. Only
`recordHumanObservation` can determine it, and it **throws** on blank evidence.
CSV rows report `unknown` / `UNVERIFIED` until evidence exists.

### Pivot, App Bar (§3.6, §3.7, P1) — `IMPLEMENTED`

For the shell's own screens only, since §3.6 is explicit that these cannot be
injected into third-party APKs.

### Privileged bridge (§10 OP02-F) — interface only, by design

`AbsentShellBridge` fails every call with a reason, so the lack of L2 is a
runtime fact the UI can display. No Shizuku dependency, no hidden-API reflection
anywhere in a shipped path.

### Still not built

| TZ item | Priority | Status |
|---|---|---|
| Golden screenshot tests (§10 OP02-B) | P0 | **missing** — needs an emulator |
| Instrumented / UI tests | P0 | **missing** — needs an emulator |
| Overlay taskbar over other apps (§6.2) | P1 | **missing** — `SYSTEM_ALERT_WINDOW` path not built |
| Accessibility service registration (§6.2) | P1 | adapter written, **service not registered** |
| Module split into `metro-core`/`metro-visual`/… (§9) | — | **not done** — cosmetic beside the device gaps |
| Per-display density recovery (§5.5) | — | **missing** — found via Taskbar, single-display assumed |

## 4. Status by TZ §14 vocabulary

| Category | Items |
|---|---|
| `implemented in code` | CapabilityGate, tile background modes and renderer, AppCompatibilityProfile, LandscapePolicyEngine, wallpaper decode/placement |
| `compiled` | all of the above; clean `assembleDebug` |
| `emulator verified` | **none** — no emulator was available |
| `physical device verified` | **none** — no device was available |
| `requires shell/root` | windowing mode (L2), task list (L2), density (L2), task resize (L3) |
| `blocked by OEM` | unknown — needs the device; HyperOS gesture behaviour on Home reassignment is the critical unknown |
| `research hypothesis` | SystemUI/WM Shell replacement — studied and **refuted** for app tiers in `OP02_SYSTEMUI_WM_SHELL_FEASIBILITY.md` |

---

## 5. §13 Definition of Done

| MUST (P0) | Status |
|---|---|
| OP01 not broken; project builds | ✅ 135 tests green, APK builds |
| Both portrait views use a real vertical Metro grid | ✅ one shared surface, tested |
| Transparent tiles with a correct shared wallpaper mask | ✅ **implemented and tested** |
| Tile drag/resize, Start ↔ App List, state saved | ✅ swipe, jump list and folders now built |
| Basic Live Tiles from real sources | ✅ clock/date/battery/geometry |
| Landscape does full-screen, and windowed where real | ✅ engine implemented and tested |
| App profiles prevent bad stretching and rotation loops | ✅ implemented and tested |
| A chosen group of third-party apps really checked | ❌ **BLOCKED — no device** |
| Top bar / taskbar / Action Center consistent with permissions | ✅ built, consent-gated, hands over to system panels |
| Back/Home/Recents and the shade survive | ⚠️ contract built and tested (12 tests); **UNVERIFIED on device — still blocking** |
| Every optional API has a fallback | ✅ by gate construction |
| No irreversible global DPI override | ✅ Tier 0 refuses all writes |
| Gradle build passed; tests listed with exact results | ✅ 135/135 |

**11 of 13 met, 1 partial, 1 not met.** The partial is the gesture criterion
(built and unit-tested, unverified on hardware); the unmet one is third-party app
verification, which is purely device-bound.

---

## 6. Environment obstacles

| Obstacle | Resolution |
|---|---|
| `android.googlesource.com`, `source.android.com`, `blogs.windows.com`, `learn.microsoft.com` all denied by network policy | Proposed and approved adding the four hosts to this repo's environment; all four verified reachable, then used |
| `gh` is scoped to this repo, so upstream repos returned 403 | Public repos clone directly through the session's git proxy; cloned Smart Dock and Taskbar at the exact cited revisions |
| Container restarted mid-session | Repo and SDK survived; re-audited `git status` and HEAD before continuing |
| No device, no emulator | Instrumented and golden tests not written; every device row left explicitly empty |

---

## 7. Next operation, in priority order

1. **Run it on the Redmi.** Everything below depends on this.
2. **Settle the gesture question first** (§7 is blocking): assign Metro as Home
   and fill `OP02_GESTURE_REGRESSION_MATRIX.csv`. If HyperOS downgrades to
   3-button navigation, that is a finding to report, not to mask — and it may
   mean the overlay path, not the Home path, is the right default.
3. **Fill `OP02_APP_COMPATIBILITY_MATRIX.csv`** with 3–5 real apps, screenshots
   required, and let `classify()` assign classes from evidence.
4. Build the gesture insets coordinator and predictive back.
5. Build `MetroTopBar` / Action Center with the system hand-off.
6. Start ↔ App List swipe, jump list, Live Folders UI.
7. Density preview before apply, debounce, crash-loop restore.
8. Only then consider the L2 Shizuku bridge — and only if the app matrix shows a
   reachable density actually buys a real tablet layout. If it does not, the
   right answer is that this device class cannot, and the shell should stop
   offering it.

---

## 8. One-line summary

OP02 settled the platform's hard limits with verified primary-source evidence
and built every part of the specification that does not require hardware — the
WP8.1 transparent-tile system, the landscape compatibility engine, the gesture
priority contract, Live Folders, the Action Center and the honesty machinery
around task sources — with 128 new tests. What remains is almost entirely the
device: no screenshot exists, no third-party app has been observed, and the
blocking gesture criterion is tested in logic but unproven on a phone.
