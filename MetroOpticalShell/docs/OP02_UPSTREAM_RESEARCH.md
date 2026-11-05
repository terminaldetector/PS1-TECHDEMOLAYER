# OP02 upstream research

Every SHA below was **independently verified** in this session by cloning the
repository at the cited revision and running `git rev-parse`. Where the result
matched the specification's claim, it says so; nothing here is copied from the
spec on trust.

**No code was copied from any of these projects.** Smart Dock is GPL-3.0, which
is incompatible with distributing it inside a differently-licensed app, so it
was read for architecture only. What follows is what the sources *prove about
the platform*, which is the part that matters.

---

## 1. Smart Dock — `axel358/smartdock`

| Field | Value |
|---|---|
| Revision | `517604a72745a1c440068b89b3f42c8248abab36` |
| License | **GPL-3.0** (verified: `LICENSE` is GNU GPL v3) |
| Inspected | yes | 
| Code reused | **no** (license incompatible with reuse here) |

Blob SHAs, all three **verified identical** to the spec's claims:

| File | Blob SHA | Spec claim | Match |
|---|---|---|---|
| `app/src/main/java/cu/axel/smartdock/utils/AppUtils.kt` | `cabb21c663704659f63870e598d4b786b5afe99d` | same | ✅ |
| `app/src/main/java/cu/axel/smartdock/services/DockService.kt` | `c570d78bd97e9c537189a769ed7a4b6202bfd049` | same | ✅ |
| `app/src/main/java/cu/axel/smartdock/utils/OnSwipeListener.kt` | `f637110b96fc19ce8bda9488032c3d45a0585c9b` | same | ✅ |

### What the source actually proves

**Freeform launching uses a hidden API via reflection.** `AppUtils.kt:375-377`:

```kotlin
val methodName = "setLaunchWindowingMode"
val method = ActivityOptions::class.java.getMethod(methodName, Int::class.javaPrimitiveType)
method.invoke(options, windowMode)
```

`setLaunchWindowingMode` is not public SDK. This is the mechanism, and it is
**not** available to us as public API — confirming TZ §2.1.

**Task resizing requires root.** `AppUtils.kt:381-387`:

```kotlin
fun resizeTask(context: Context, mode: String, taskId: Int, dockHeight: Int) {
    val bounds = makeLaunchBounds(context, mode, dockHeight)
    DeviceUtils.runAsRoot("am task resize " + taskId + " " + ...)
}
```

`DeviceUtils.runAsRoot` writes to a `su` process's stdin. So window *resize* in
Smart Dock is an **L3/root** capability, not L0 or L2.

**The real task list comes from Shizuku, not from public API.**
`wrappers/ActivityManagerWrapper.kt`:

```kotlin
binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity"))
activityManager = IActivityManager.Stub.asInterface(binder)
```

with `dev.rikka.shizuku:api:13.1.5` in `app/build.gradle`. The windowing mode of
each task is then read by reflection on `ActivityManager.RunningTaskInfo`
(`AppUtils.kt:184`).

**The unprivileged fallback is usage statistics, not a window list.**
`AppUtils.getRecentTasks` uses `UsageStatsManager.queryUsageStats(...)` sorted by
`lastTimeUsed`. This is *inferred recency*, not a list of live windows, and it
needs `PACKAGE_USAGE_STATS` (a special-access permission the user grants in
Settings).

This single file is the clearest possible evidence for TZ §4.3 and §6.3: the
most mature open-source implementation of this feature set **also** cannot get a
true window list without a privileged bridge, and falls back to a heuristic.

**Declared permissions** include `WRITE_SECURE_SETTINGS`, `MANAGE_USERS`,
`ACCESS_SUPERUSER`, `GET_TASKS`, `REORDER_TASKS`, `PACKAGE_USAGE_STATS`,
`SYSTEM_ALERT_WINDOW`. The first three cannot be held by an ordinary install;
they are effective only via root or an ADB/Shizuku grant.

---

## 2. Taskbar — `farmerbb/Taskbar`

| Field | Value |
|---|---|
| Revision | `e2a00b9a3f3adda069059abb9aebcb8b4e18954f` |
| License | **Apache-2.0** (verified: `LICENSE` is Apache License 2.0) |
| Inspected | yes |
| Code reused | no (architecture only) |

| File | Blob SHA | Spec claim | Match |
|---|---|---|---|
| `app/src/main/java/com/farmerbb/taskbar/util/U.java` | `b88f1d179909b099dfdec5a6f34ca0af9bc0d2a0` | same | ✅ |

### The "freeform without root" claim, resolved

The spec warns not to treat Taskbar's README as a guarantee. The source settles
it. `U.java:850-856`:

```java
public static boolean hasFreeformSupport(Context context) {
    return canEnableFreeform(context)
            && (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
            || Settings.Global.getInt(context.getContentResolver(), "enable_freeform_support", 0) != 0
            || (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1
            && Settings.Global.getInt(context.getContentResolver(), "force_resizable_activities", 0) != 0));
}
```

Taskbar does **not** enable freeform. It *detects* whether the device already
has it — via exactly two live signals plus one legacy one. It is "without root"
only in the sense that it does not try to turn freeform on.

**This independently validates our OP01 probe.** `FreeformLaunchAdapter.probe()`
already reads precisely those two signals:

- `PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT`
- `Settings.Global "enable_freeform_support"`

So our Tier 0 detection matches the most mature implementation in the field. We
additionally refuse to report `OBSERVED` without a human observation, which
Taskbar does not do.

Taskbar also reaches `setLaunchWindowingMode` by reflection (`U.java:988`), the
same hidden API as Smart Dock.

### Density changes use a hidden WindowManager API

`U.java:1933-1951`:

```java
Class.forName("android.view.IWindowManager")
        .getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class)
        .invoke(getWindowManagerService(), displayID, density, USER_CURRENT_OR_SELF);
```

reached through `android.view.WindowManagerGlobal#getWindowManagerService`, with
`clearForcedDisplayDensityForUser` for reset. Taskbar declares
`WRITE_SECURE_SETTINGS`, which an ordinary install cannot hold.

**Consequence for us:** this is a second, independent confirmation that
system density cannot be changed from an unprivileged APK. It also shows the
*shape* a future L2/L3 adapter would take — and note that `clearForced...` is
the equivalent of `wm density reset`, which is why our rollback distinguishes
"had an override" from "had none".

---

## 3. AOSP Launcher3 / Quickstep

Source: `https://android.googlesource.com/platform/packages/apps/Launcher3/`,
`quickstep/AndroidManifest.xml` on `refs/heads/main`, fetched and read in this
session.

### Becoming the Recents/gesture provider is structurally closed to a normal app

The manifest declares:

```xml
<service android:name="com.android.quickstep.TouchInteractionService"
     android:permission="android.permission.STATUS_BAR_SERVICE">
    <intent-filter>
        <action android:name="android.intent.action.QUICKSTEP_SERVICE"/>
    </intent-filter>
</service>
```

**Verified: the spec's §7.3 claim about `STATUS_BAR_SERVICE` is correct.**

The same manifest requires, among others:

`START_TASKS_FROM_RECENTS`, `REMOVE_TASKS`, `MANAGE_ACTIVITY_TASKS`,
`INTERNAL_SYSTEM_WINDOW`, `STATUS_BAR`, `STATUS_BAR_SERVICE`,
`STOP_APP_SWITCHES`, `SET_ORIENTATION`, `READ_FRAME_BUFFER`, `MONITOR_INPUT`,
`ALLOW_SLIPPERY_TOUCHES`, `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`,
`WRITE_SECURE_SETTINGS`, `SYSTEM_APPLICATION_OVERLAY`.

These are signature/privileged permissions. `MONITOR_INPUT` and
`ALLOW_SLIPPERY_TOUCHES` in particular are what let Quickstep observe and take
over a gesture that began on another app's surface.

**Conclusion, with evidence:** no amount of Compose gesture handling makes Metro
a Quickstep provider. The platform gates it on permissions only a
platform-signed or preloaded app can hold. Writing our own swipe handlers inside
our own window is a *different thing* and must never be presented as the same —
exactly TZ §7.3.

---

## 4. Microsoft primary sources — the WP8.1 transparent tile mechanism

Both articles were fetched and read in this session.

### "Developers: Make your Tiles Transparent for Windows Phone 8.1" (19 May 2014)

Quoting the mechanism:

> the new Start Background feature, which allows people to set their Start
> screen to have a background image displayed behind the tiles […] in order for
> users to see the image behind the tiles, the app's tile needs to have a
> transparent background

and the developer steps: use a transparent `.png` for the tile; set the
manifest `Background color` to `transparent`; for dynamically generated live
tiles, generate a transparent PNG.

### "Deep dive: Windows Phone 8.1 Start screen backgrounds" (29 Apr 2014)

Confirms there is **one** user-chosen image with **one** transform:

> Once you've found the perfect photo you'll then be asked to crop it. Simply
> pinch the screen to zoom in or out and move the image around to find the best
> position.

### What this settles for our implementation

1. There is **one** shared background image and **one** transform (crop, zoom,
   offset) — not a per-tile image.
2. A tile is "transparent" because its own asset has transparency; the shared
   image behind it then shows through.
3. Therefore the image must be sampled in a **single shared coordinate space**,
   so the picture lines up across adjacent tiles.

This is why `WP81_IMAGE_MASK` must draw one shared bitmap offset by each tile's
position in the shared canvas, clipped to the tile — and must **not** draw the
wallpaper independently inside each tile, which would repeat or stretch it per
cell and destroy the continuity that makes the effect read.

**Honest limit:** neither article states the colour of the inter-tile gaps in
words. The black-gap appearance is asserted by the TZ (§3.2) and matches the
known WP8.1 look; we implement it that way and record here that it comes from
the TZ plus the published screenshots, not from a sentence we can quote.

---

## 5. Sources named by the spec but NOT studied in this operation

Stated plainly rather than implied:

| Source | Status | Why |
|---|---|---|
| `LawnchairLauncher/lawnchair` | **not inspected** | Large repo; its value is QuickSwitch/Recents integration, which §3 above already settles from AOSP itself. |
| QuickSwitch docs | **not inspected** | Same. Root-only integration, out of scope for L0/L1. |
| `RikkaApps/Shizuku` | **not inspected directly** | Its API surface was observed in use via Smart Dock's `ActivityManagerWrapper`, which is the evidence that mattered. |
| AOSP WM Shell / `CentralSurfacesImpl.java` | **not inspected** | Deferred with the SystemUI feasibility study (§6.4 is explicitly research-only). |
| YoukiDEX | **not inspected** | Archived Sept 2026; spec itself says treat as non-maintained. |
| MS Live Folders / Action Center / Metro principles articles | **not fetched** | Live Folders and Action Center are implemented from the TZ's own description this operation; no primary-source claim is made about them. |

---

## 6. Evidence block (TZ §8.9 form)

```yaml
- upstream:
    repository: "axel358/smartdock"
    commit_sha: "517604a72745a1c440068b89b3f42c8248abab36"
    license: "GPL-3.0"
    file: "app/src/main/java/cu/axel/smartdock/utils/AppUtils.kt"
    symbol: "AppUtils.getActivityOptions / resizeTask / getRecentTasks"
  application:
    capability: "freeform launch, task resize, task enumeration"
    privilege_tier: "L2 (Shizuku) and L3 (root for resize)"
    proposed_metro_module: "privileged-experimental/TaskControlProbe (interface only)"
  validation:
    inspected_source: true
    built: false
    tested_on_hyperos: false
    notes: "blob SHAs verified identical to spec; no code copied (GPL-3.0)"
  risks:
    - "hidden API setLaunchWindowingMode may be blocked or absent"
    - "root requirement for resize is unacceptable as a baseline"
    - "GPL-3.0 forbids reuse in this codebase"

- upstream:
    repository: "farmerbb/Taskbar"
    commit_sha: "e2a00b9a3f3adda069059abb9aebcb8b4e18954f"
    license: "Apache-2.0"
    file: "app/src/main/java/com/farmerbb/taskbar/util/U.java"
    symbol: "U.hasFreeformSupport / U.setDensity"
  application:
    capability: "freeform capability detection; density override"
    privilege_tier: "L0 for detection, L2/L3 for density"
    proposed_metro_module: "runtime/FreeformLaunchAdapter (already implemented in OP01)"
  validation:
    inspected_source: true
    built: false
    tested_on_hyperos: false
    notes: "confirms our OP01 probe reads the same two signals; no code copied"
  risks:
    - "README 'freeform without root' means detection only, not enabling"

- upstream:
    repository: "platform/packages/apps/Launcher3"
    commit_sha: "refs/heads/main (read 2026-11-05)"
    license: "Apache-2.0"
    file: "quickstep/AndroidManifest.xml"
    symbol: "com.android.quickstep.TouchInteractionService"
  application:
    capability: "Recents / system gesture provider"
    privilege_tier: "L4 (platform signature)"
    proposed_metro_module: "none - documented as unreachable"
  validation:
    inspected_source: true
    built: false
    tested_on_hyperos: false
    notes: "STATUS_BAR_SERVICE + MONITOR_INPUT + MANAGE_ACTIVITY_TASKS confirmed"
  risks:
    - "any claim of replacing Recents from a normal APK is false"
```
