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

**135 unit tests, 0 failures** (86 → 135, +49). Clean `assembleDebug`, APK ~11.3 MB.

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

## 3. What was NOT built

Stated plainly, because §13 forbids calling a pretty screen complete.

| TZ item | Priority | Status |
|---|---|---|
| Live Folders UI (§3.4) | P1 | **missing** — groups exist in data only |
| Start ↔ App List swipe (§3.5) | P0 | **missing** — header button only |
| Alphabet jump list (§3.5) | P0 | **missing** — letter headers exist |
| Live tile flip/cycle (§3.3) | P0/P1 | **missing** |
| Notification badges (§3.3) | P1 | **missing** — needs L1 consent |
| `MetroTopBar` / Action Center (§6.1) | P0/P1 | **missing** |
| System panel adapter (§6.2) | P1 | **missing** |
| Gesture insets coordinator (§7.2) | **P0, blocking** | **missing** |
| Predictive back (§7.2) | P0 | **missing** |
| `AppLayoutProbe` runtime wiring (§5.2) | P0 | **model only** — the profile and observation types exist; nothing records them yet |
| Density preview before apply (§5.5.4) | P0 | **missing** |
| Crash-loop auto-restore (§5.5.7) | P0 | **missing** |
| Debounce / recursion guard (§5.6) | P0 | **missing** |
| Panorama/Pivot, App Bar (§3.6, §3.7) | P1 | **missing** |
| Shizuku bridge (§10 OP02-F) | P2 | **interface only, by design** |
| Golden screenshot tests (§10 OP02-B) | P0 | **missing** — needs an emulator |
| Module split into `metro-core`/`metro-visual`/… (§9) | — | **not done** — single module retained; the split is cosmetic next to the device gaps |

The honest summary: of OP02's seven sub-operations, **A is done**, **B and C are
partially done** (the headline items of each), and **D, E, F, G are largely not
started**. §7 — gestures, the blocking criterion — has code neither written nor
verifiable here.

---

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
| Tile drag/resize, Start ↔ App List, state saved | ⚠️ drag/resize ✅; **Start↔AppList swipe missing** |
| Basic Live Tiles from real sources | ✅ clock/date/battery/geometry |
| Landscape does full-screen, and windowed where real | ✅ engine implemented and tested |
| App profiles prevent bad stretching and rotation loops | ✅ implemented and tested |
| A chosen group of third-party apps really checked | ❌ **BLOCKED — no device** |
| Top bar / taskbar / Action Center consistent with permissions | ❌ **missing** |
| Back/Home/Recents and the shade survive | ❌ **UNVERIFIED — blocking** |
| Every optional API has a fallback | ✅ by gate construction |
| No irreversible global DPI override | ✅ Tier 0 refuses all writes |
| Gradle build passed; tests listed with exact results | ✅ 135/135 |

**9 of 13 met, 1 partial, 3 not met.** The three unmet are the device-dependent
ones plus the top panel.

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

OP02 settled the platform's hard limits with verified primary-source evidence,
built the WP8.1 transparent-tile system and the landscape compatibility engine
with 49 new tests — and leaves the gesture criterion, the top panel and every
device verification explicitly unfinished.
