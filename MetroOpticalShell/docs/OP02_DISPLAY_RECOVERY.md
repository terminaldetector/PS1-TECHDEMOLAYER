# OP02 display recovery

Supersedes nothing: [DISPLAY_ROLLBACK.md](DISPLAY_ROLLBACK.md) from OP01 remains
the operator procedure. This file adds what OP02 learned from the upstream
sources and states what is still missing.

## What changed in OP02's understanding

OP01 documented `wm density` over ADB. OP02 found the **second** mechanism, in
`farmerbb/Taskbar@e2a00b9`, `U.java:1933`:

```java
Class.forName("android.view.IWindowManager")
        .getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class)
        .invoke(getWindowManagerService(), displayID, density, USER_CURRENT_OR_SELF);
```

with `clearForcedDisplayDensityForUser` for the reset. Three consequences:

1. **It confirms the OP01 design.** `clearForced...` exists as a *separate call*
   from setting a number — exactly the distinction our rollback already makes
   between "the user had an override" and "the user had none". Resetting is not
   the same as writing a value back, in the platform's own API shape.
2. **It is still privileged.** Reached by reflection on a hidden interface, and
   Taskbar declares `WRITE_SECURE_SETTINGS`. An ordinary install cannot hold it,
   so this is an L2/L3 mechanism, not a way around the Tier 0 refusal.
3. **It is per-display.** `displayID` is a parameter. A multi-display device can
   be left in a mixed state, which the single-display assumption in OP01's
   rollback does not cover. Recorded as a gap.

## The recovery contract

| Step | Status |
|---|---|
| Record the baseline *before* any change | ✅ implemented (`recordBaselineOnce`) |
| Distinguish "had an override" from "had none" | ✅ implemented and tested |
| Refuse changes below a device-derived readability guard | ✅ implemented and tested |
| Require explicit consent immediately before applying | ✅ implemented and tested |
| Verify by re-measuring rather than assuming | ✅ implemented and tested |
| Report `NOT_OBSERVED` when the measurement disagrees | ✅ implemented and tested |
| Host-side tool that records, applies and verifies | ✅ written, syntax-checked, **never executed** |
| **Preview the new geometry before applying it** | ✅ implemented and tested (`DisplayProfileManager.preview`) |
| **Automatic restore on crash-loop** | ⚠️ `CrashLoopGuard` disables our own display-profile feature after two failures; it does **not** restore system density, which still needs the operator |
| **Debounce / recursion guard on profile switching** | ✅ implemented and tested (`OrientationDebouncer`) |
| **Per-display recovery** | ❌ **missing** — found via Taskbar, not yet handled |

One gap remains fully open — per-display recovery — and one is partial: the
crash-loop guard switches our own feature off but cannot put the system's
density back, because at Tier 0 the shell cannot write it in the first place.
That asymmetry is inherent, not an oversight.

## Manual recovery, unchanged

If you had **no** override before:

```bash
adb shell wm density reset
```

If you **did** have one (the number `wm density` printed as `Override density`):

```bash
adb shell wm density <your-original-number>
```

A reboot does **not** clear a `wm density` override, and neither does safe mode.
ADB keeps working however small the UI becomes, which is why the baseline is
written to disk before anything is attempted, and why the app refuses to make
the change itself.

## Honest status

**No density change has ever been applied or rolled back on hardware by this
project.** Every row above marked ✅ is `TESTED` (automated), not
`DEVICE_VERIFIED`. The recovery path is designed and unit-tested; it is
unproven.
