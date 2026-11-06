# Metro Optical Shell — v4 implementation report

**Date:** 6 November 2026
**Branch:** `claude/new-session-rlp3eq`
**Audited HEAD at entry:** `244c8965323a980e521b71eb190b0bc2d1d90a4a` — **identical to the checkpoint named in TZ v4 §1**, working tree clean
**Resulting commit:** `a014f48f90a512fc27d91c4794677a270ecc0a42`
**App root:** `MetroOpticalShell/` · release `com.metro.opticalshell` · debug `com.metro.opticalshell.debug`
**Environment:** Linux container, JDK 21 / Gradle 8.14.3, Android SDK 35. **No device, no emulator.**

---

## 1. Entry audit (§1)

Performed in the order the spec prescribes.

| Check | Result |
|---|---|
| `git status --short` | clean (0 entries) |
| `git branch --show-current` | `claude/new-session-rlp3eq` |
| `git rev-parse HEAD` | `244c896…` = the checkpoint, so no newer work was at risk of being overwritten |
| Android SDK | platform 35 present |
| `assembleDebug` | succeeds |
| `testDebugUnitTest` | 214 passing at entry |
| `lintDebug` | 0 errors at entry |

### Implementation state at entry

| State | Items |
|---|---|
| works / tested | environment-presentation model, tile packer, WP8.1 wallpaper mask, display arithmetic, gesture contract, folders, jump list, crash guard, task-source model |
| integrated but unverified | top bar, Action Center, desktop, taskbar, optical layer, freeform probe |
| **mock / dead code** | **`LandscapePolicyEngine.decide()` — never called from anywhere** |
| disabled | `HomeAlias` (`enabled="false"`, no enable path) |
| needs system access | windowing mode, task list, density, task resize |
| absent | accessibility service registration, overlay path, golden tests, instrumented tests |

**All seven FIX items were independently confirmed in the source before any
change.** The spec's audit was accurate on every point, including the two I
would not have found by re-reading my own intent: that the policy engine was
dead code, and that my own test asserted the FIX-05 bug as correct behaviour.

---

## 2. The fixes

### FIX-01 — Home candidacy

`HomeAlias` was `android:enabled="false"` and **no code anywhere could enable
it**; `RoleManager` appeared only inside a doc comment. So the chooser showing
only *System launcher* was correct behaviour for what was built.

The alias now ships **enabled**, and the reasoning matters: a `HOME` intent
filter makes a component a *candidate* only — Android still requires the user to
pick it. So this cannot take over Home silently, satisfying §7's checkpoint and
§7's "no component makes itself Home" rule simultaneously.

`HomeRoleController` adds: candidacy registration and **withdrawal** via
`setComponentEnabledSetting`, `RoleManager.ROLE_HOME` request on API 29+, the
documented `ACTION_HOME_SETTINGS` fallback below that, and a five-field status
model with five verdicts. `ROLE_AVAILABLE_BUT_NOT_LISTED` is the shape an OEM
restriction takes, and its message insists on diagnostics before blaming the OEM
— because a disabled component and a different installed variant look identical
from inside the app. The four `dumpsys` / `pm` commands are printed in-app.

### FIX-02 — per-permission gating

The defect: `stateFor()` asked `held.highestTier.ordinal >= L1`, so **any** L1
grant unlocked **every** L1 capability. Notification access implied the Home
role, the overlay and usage stats.

Each capability now declares exactly one `RequiredGrant`, and the gate checks
that grant alone. Seven regression tests, including the two conflations the spec
names explicitly: notification + overlay access does **not** grant `resizeTask`
or `setWindowingMode`, and usage-stats access does **not** grant a true task
list. `highestTier` survives as informational only, with a comment saying so.

`MainActivity.heldPrivileges()` now reads each grant from its own live source:
`isDefaultHome`, the notification-listeners setting, `Settings.canDrawOverlays`,
and `AppOpsManager` for usage stats.

### FIX-03 — one launch path

The defect: `ShellViewModel.launch()` called `StandardAppLauncher` directly.
Every tile tap, app-list tap and shortcut bypassed the policy engine, so
`LandscapePolicyEngine.decide()` was **dead code in the running app** — unit
tested, never executed.

`AppLaunchOrchestrator` implements §4.3's seven steps in order, and only
`launch()` is public. The capability check is made for the **specific
operation** (`SET_WINDOWING_MODE` for a windowed launch, `LAUNCH_APP`
otherwise), per §4.3 step 4. Telemetry records `REQUESTED`; only a measurement
may say `OBSERVED`.

Should the handler ever be missing, the fallback still starts the app — the user
is never stuck — but the launch is flagged and the diagnostics screen shows a
warning that policy was bypassed. A wiring fault becomes visible instead of
silently restoring the old behaviour.

### FIX-04 — no `REQUESTED` before a request

`probe()` returned `REQUESTED` from capability flags alone. A new `CAPABLE`
state carries "the device supports it, nothing has been attempted", and
`REQUESTED` is set only after `launchWindowed()` actually asks.

### FIX-05 — recreation is not a defect

Any `recreatedOnRotate == true` classified an app as unstable. But Activity
recreation on a configuration change is **documented Android behaviour** that
most apps handle correctly, so this would have mislabelled the majority of
well-behaved apps — and my own test asserted it as correct.

Instability now requires a real signal: `stateLost`, `crashed`,
`brokenGeometry`, `inputLost` or `restartLoop`. The old test was replaced with
three that pin the corrected behaviour.

### FIX-06 — live gesture geometry

The geometry was `remember`ed keyed on the `View` instance. A `View` is the
**same object** across a rotation, so `view.height`, `view.width` and the
captured insets went stale exactly when they mattered — and this sits on the
blocking safety path.

Everything now comes from state-backed Compose sources that recompose on their
own: `WindowInsets.systemGestures` / `mandatorySystemGestures` / `ime`, and
`LocalConfiguration.screenWidthDp/screenHeightDp`, which describe the current
**window** and so also cover split-screen and freeform resizes.

### FIX-07 — build variants

`HomeStatus.installedPackage` reports the running package in every diagnosis,
and `HOME_AND_GESTURE_VALIDATION.md` §2 states plainly that the two variants are
different packages that install side by side, so a result for one is not
evidence about the other.

---

## 3. Two further real bugs, caught by lint

Both the same class of error, both crashes:

| API | Requires | minSdk | Impact |
|---|---|---|---|
| `View.setSystemGestureExclusionRects` | 29 | 26 | crash on Android 8–9, on the **blocking gesture path** |
| `AppOpsManager.unsafeCheckOpNoThrow` | 29 | 26 | crash on Android 8–9 |

Both guarded. Worth recording that lint found what review did not.

---

## 4. Verification

```
assembleDebug        SUCCESS
testDebugUnitTest    226 tests, 0 failures
lintDebug            0 errors, 30 warnings
app-debug.apk        ~11.3 MB
```

All 30 lint warnings are `GradleDependency` / `AndroidGradlePluginVersion`
version notices, which are expected: §OP01-A.3 requires pinned versions and
forbids dynamic ones.

Test growth: 86 (OP01) → 135 → 214 → **226**.

New this operation: `FixVerificationTest` (7, FIX-02), `FreeformStatusTest` (3,
FIX-04), three replacing the FIX-05 assertion.

**No instrumented or golden tests.** None were written, because no emulator was
available and committing unrun instrumented tests would be exactly the
unverified claim §0 forbids.

---

## 5. Status by the §13 vocabulary

| Level | Items |
|---|---|
| `SOURCE_IMPLEMENTED` | Home controller, orchestrator, Action Center, notification bridge, top bar, desktop, Pivot/App Bar, wallpaper picker |
| `TESTED` | capability gate, launch policy, tile packer, wallpaper mask geometry, gesture contract, debouncer, crash guard, folders, jump list, display arithmetic, profile preview, task-source model |
| `SYSTEM_REQUESTED` | **nothing** — no request has been made to a real system |
| `DEVICE_OBSERVED` | **nothing** |
| `BLOCKED` | windowing mode (L2), task list (L2), task resize (L3), density write (L2), accessibility panels (service unregistered), overlay path, Recents provider and status bar (L4, refuted) |

---

## 6. §15 Definition of Done

> "Remotely complete when code is integrated, the build and available tests
> pass, a Home candidate is registered, app launching is centralised, system
> adapters return correct results, tiled Metro is preserved, and the test
> protocols are ready to run on the Redmi."

| Requirement | Status |
|---|---|
| Code integrated into the current branch | ✅ |
| Build and available tests pass | ✅ 226/0, lint 0 errors |
| Home candidate registered | ✅ alias enabled + controller (unverified on device) |
| App launching centralised | ✅ single orchestrator; bypass detected and surfaced |
| System adapters return correct results | ✅ per-operation gating; `CAPABLE` ≠ `REQUESTED` ≠ `OBSERVED` |
| Tiled Metro preserved | ✅ one shared surface for both portraits |
| Protocols ready for the Redmi | ✅ `DEVICE_TEST_MATRIX.csv`, 24 rows, all `NOT_RUN` |

**The remote engineering operation is complete by that definition.**

> "The product goal is not achieved until physically confirmed: Home,
> gesture navigation, at least one real landscape multitasking mode, data
> integrity, and actual third-party behaviour after density/size changes."

**The product goal is not achieved.** None of those five has been physically
confirmed. Nothing has ever run on a device.

---

## 6a. First hardware observations (6 November 2026)

The APK was installed on a Redmi/HyperOS device. Two facts, both now
`DEVICE_OBSERVED`:

1. **Metro appears in the Default Home screen list.** FIX-01 is confirmed on
   hardware. Before it the alias was disabled with no code able to enable it, so
   this was impossible by construction. The adaptive icon and label render
   correctly too.
2. **The ROM refuses the selection:** "Only verified apps can be set as your
   default launcher."

The second is a Xiaomi allow-list check **inside Settings**, applied at
selection time — after candidacy succeeds and before any role is granted.
Nothing in `RoleManager` or `PackageManager` rejected us, and the part under our
control works.

Added in response: a `OEM_REFUSED_SELECTION` verdict, operator recording of the
refusal (the shell cannot see a Settings dialog, so this is a human observation
like a freeform one), and the documented shell-level route
`cmd package set-home-activity`, which does not pass through Settings' vendor
check. That route is **UNVERIFIED**; a vendor can block it at the package
manager too, and if it does, that failure is the answer.

This also means the blocking gesture question cannot be reached through Settings
on this ROM at all — it now depends on the shell route working first.

## 7. The hardware experiments, in priority order

1. ~~Install the debug APK and check the Home chooser.~~ **DONE 6 Nov 2026:
   Metro is listed; the ROM refuses the selection.** Next: try
   `cmd package set-home-activity` over ADB, which bypasses the Settings policy
   check. If that also fails, the Home route is genuinely closed on this ROM and
   the overlay path becomes the product's default.
2. **Assign Metro as Home and test Back/Home/Recents/shade.** The blocking
   criterion. If HyperOS downgrades to three-button navigation, Home integration
   must **not** be called successful, and the right product answer is to make the
   overlay path the default rather than the Home role — a design change, not a
   setting.
3. **Launch apps through the orchestrator** and confirm the policy actually
   applies; check the diagnostics screen shows no bypass warning.
4. **Try split-screen with real apps.** This is the minimum viable
   multitasking the spec names, and it needs no privileges.
5. **Record freeform** via the diagnostics buttons — `CAPABLE` must become
   `OBSERVED` or `NOT_OBSERVED` by a human looking at the screen.
6. **Fill `DEVICE_TEST_MATRIX.csv`** for 8 app classes, screenshots required for
   any claimed layout change.
7. **Density change and rollback**, confirming the restore by reading the
   configuration back.
8. Only then L2 (Shizuku), and only if the matrix shows a reachable density
   actually buys a real tablet layout. If it does not, the honest conclusion is
   that this device class cannot, and the shell should stop offering it.

---

## 8. One paragraph

v4's audit was right about all seven defects, and two of them were things I
would not have found by re-reading my own intent: the landscape policy engine
was dead code, and one of my own tests asserted a bug as correct behaviour. All
seven are fixed, each with the reasoning recorded; lint then caught two
unguarded API-29 calls that would have crashed on Android 8–9. The shell now has
one launch path, per-operation permission gating, a real Home candidacy flow and
gesture geometry that survives rotation. What it still has not got is a single
minute of runtime on a phone, and every product-level claim in this project
remains unverified until it does.
