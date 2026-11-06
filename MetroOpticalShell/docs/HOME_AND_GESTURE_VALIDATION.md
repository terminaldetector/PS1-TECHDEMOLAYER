# Home role and gesture validation

## 1. What the manifest actually declares

Real values, read from `app/src/main/AndroidManifest.xml` at this commit.

```xml
<activity android:name=".MainActivity" android:exported="true" ...>
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<activity-alias
    android:name=".HomeAlias"
    android:targetActivity=".MainActivity"
    android:exported="true"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity-alias>
```

`MAIN` + `HOME` + `DEFAULT` on the alias, `MAIN` + `LAUNCHER` retained on the
Activity so the shell is independently launchable and recoverable. That matches
TZ v4 §7's requirement exactly.

### Why the alias now ships enabled (FIX-01)

The previous build had `android:enabled="false"` **and no code anywhere that
could enable it** — `RoleManager` appeared only inside a documentation string.
So Metro could never appear in the Home chooser, and the "promised enable path"
genuinely did not exist.

Enabling the alias is safe, and this is the reasoning: a component with a `HOME`
intent filter only becomes a *candidate* in Android's chooser. The system still
requires the user to select it. So an enabled alias cannot take over Home
silently, which satisfies both §7's checkpoint ("after installing, Metro appears
in the system Home list") and §7's rule that no component makes itself Home
without a conscious choice.

`HomeRoleController.unregisterAsCandidate()` disables the alias again through
`setComponentEnabledSetting`, so the candidacy is reversible from inside the app.

## 2. Build variants (FIX-07)

| Variant | applicationId |
|---|---|
| release | `com.metro.opticalshell` |
| debug | `com.metro.opticalshell.debug` |

These are **different packages** and install side by side. A Home assignment
made for one says nothing about the other, and `dumpsys` output for one is not
evidence about the other. `HomeStatus.installedPackage` therefore reports the
running package in every diagnosis.

**Pick one channel for hardware testing and stay on it.** Mixing them is the
most likely cause of a confusing Home result.

## 3. Home status model

`HomeRoleController.status()` reports five independent facts, never collapsed:

| Field | Source |
|---|---|
| `candidateRegistered` | `PackageManager.getComponentEnabledSetting`, falling back to the manifest's declared `enabled` |
| `roleAvailable` | `RoleManager.isRoleAvailable(ROLE_HOME)`, API 29+ only |
| `isCurrentHome` | `RoleManager.isRoleHeld`, or the resolved Home package equalling ours |
| `resolvedHomePackage` | `resolveActivity(MAIN+HOME, MATCH_DEFAULT_ONLY)` |
| `availableHomePackages` | `queryIntentActivities(MAIN+HOME, MATCH_ALL)` |

Verdicts: `CANDIDATE_NOT_REGISTERED` · `CANDIDATE_VISIBLE` ·
`ROLE_AVAILABLE_BUT_NOT_LISTED` · `ROLE_GRANTED` · `UNKNOWN`.

`ROLE_AVAILABLE_BUT_NOT_LISTED` is the shape an OEM restriction takes. The
verdict's own message insists on diagnostics before blaming the OEM, because a
disabled component and a different installed variant look identical from inside
the app.

## 4. Verification commands

Run these and paste the output here. They are also shown in-app, on the
diagnostics screen.

```bash
adb shell dumpsys package com.metro.opticalshell.debug | grep -A3 HomeAlias
```

```bash
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```

```bash
adb shell pm get-home-activities
```

```bash
adb shell dumpsys package preferred-activities | grep -i home
```

## 5. Home results — FIRST DEVICE OBSERVATIONS

**Device: Xiaomi/Redmi on HyperOS. Observed 6 November 2026.**

| Field | Value |
|---|---|
| Appears in the Default Home screen list? | **YES — `DEVICE_OBSERVED`** |
| Icon and label render correctly? | **YES — `DEVICE_OBSERVED`** (four-tile adaptive icon, "Metro Optical Shell") |
| Selection accepted? | **NO — `DEVICE_OBSERVED`, refused by the ROM** |
| Refusal text | "As launcher apps from third parties may result in problems like data leaks, abnormal battery drain, and lagging. Only verified apps can be set as your default launcher." |
| Verdict | `OEM_REFUSED_SELECTION` |
| Device / HyperOS build / API | _still to be collected from the diagnostics screen_ |
| Installed variant | _to confirm with `pm list packages | grep metro`_ |

### What this proves

**FIX-01 is confirmed on hardware.** Before it, `HomeAlias` was
`android:enabled="false"` with no code anywhere able to enable it, so Metro
could not appear in this list under any circumstance. It now does. The manifest
declaration, the alias target and the icon are all correct.

### What blocks it

The refusal is a **Xiaomi policy check inside Settings**, not a missing
permission and not an Android limitation:

- Metro *is* registered — the system lists it, which is the part we control.
- The dialog is a vendor allow-list ("only verified apps"), applied at the
  moment of selection.
- Nothing in `RoleManager` or `PackageManager` rejected us.

So this is the §15 case: "for a function closed by HyperOS, what is required is
not 'impossible, skipping' but the exact point of failure, the permissions, a
reproducible test, an available alternative mechanism and its limits."

**Exact point of failure:** Settings' launcher verification, at selection time —
after candidacy succeeds and before any role is granted.

### The alternative mechanism

`cmd package set-home-activity` is AOSP's own PackageManager shell command. It
sets the preferred Home activity directly and does **not** pass through
Settings' vendor policy check. It needs ADB (L2), not root, and the shell
cannot run it itself.

```bash
adb shell cmd package set-home-activity com.metro.opticalshell.debug/com.metro.opticalshell.HomeAlias
```

```bash
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```

Revert at any time:

```bash
adb shell cmd package set-home-activity com.miui.home/com.miui.home.launcher.Launcher
```

**Status: UNVERIFIED.** A vendor can also block this at the package-manager
level. If the command fails, that failure is the real answer and must be
recorded as such rather than retried differently.

### Its limits, stated before trying

Even if the command succeeds, it settles only *who is Home*. It says nothing
about whether HyperOS keeps gesture navigation afterwards — which is the
separate blocking question in §6 below, and the one that actually decides the
product's shape.

## 6. Gesture contract

The priority order of §6.2, enforced in `GesturePriorityContract` and pinned by
12 tests:

```
system mandatory > accessibility/security > system bars >
shell navigation > tile interactions > decorative
```

Properties the tests hold:

- **No claim at all while the IME is up.** Back must never be lost to a
  keyboard-covering layout.
- **Claims are clipped above the mandatory strip**, even if the whole screen is
  requested.
- **The total claim never exceeds the platform's 200dp per-edge cap**, allocated
  highest-priority first, rather than handing Android rects it would discard.
- **Only tile surfaces claim a horizontal swipe.** The desktop opens Start with
  a button.
- Exclusion rects are cleared on dispose, so a stale one cannot keep stealing
  Back after its screen is gone.

FIX-06: the geometry feeding all of this is now read from state-backed Compose
sources, so it is correct after rotation, an IME change, a density change and a
split/freeform resize. Previously it was captured once against the `View`
instance, which does not change on rotation — so the numbers were stale exactly
when they mattered.

API note: `setSystemGestureExclusionRects` requires API 29 and `minSdk` is 26.
Below 29 there is no gesture navigation to exclude from, so the call is skipped.
This was a real crash on Android 8–9 until lint caught it.

### The blocking question

TZ v4 §6.2 and §7: if HyperOS switches navigation to three buttons when a
third-party Home is assigned, **Home integration must not be declared
successful**. `NavigationStatus.DOWNGRADED_BY_OEM` exists for exactly that, is
flagged `isBlocking`, and its message tells the user how to revert.

**This is unverified.** It needs the device, and it is the single most important
hardware experiment in the project: if navigation is downgraded, the overlay
path — not the Home role — should become the default, which changes the product
design rather than just a setting.

## 7. Gesture results — `NOT COLLECTED`

See `DEVICE_TEST_MATRIX.csv`; every gesture row is `NOT_RUN`.
