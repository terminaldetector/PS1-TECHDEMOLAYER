# Privileged bridge

## 1. Current state: no bridge ships

`AbsentShellBridge` implements the full `PrivilegedShellBridge` interface and
fails **every** call with a stated reason. There is no Shizuku dependency in the
build, and no reflection into hidden APIs in any shipped code path.

TZ v4 §8 asks for `connect / check / execute / verify / disconnect` rather than
an empty interface. That is a fair criticism of a stub, and the honest position
is this: the operations below cannot be implemented *and verified* without a
device to run them on and a user decision to install Shizuku. What ships is the
seam plus the refusal, so the absence of L2 is a runtime fact the UI displays
rather than a hidden branch.

## 2. What each level would buy — and its exact cost

All verified from primary sources (see `OP02_UPSTREAM_RESEARCH.md`).

| Level | Mechanism | Operations | Cost / limit |
|---|---|---|---|
| `L0` | ordinary APK | tiles, desktop UI, launching, public intents, calibration | cannot touch other apps' windows |
| `L1` | Home / Notification / Overlay / Accessibility / Usage Access | Home shell, badges, compatible panels, global actions, recency heuristics | **per-permission**; see FIX-02 |
| `L2` | ADB / Shizuku shell UID | density changes, task and window operations within shell rights, deeper diagnostics | shell UID is **not** a platform signature |
| `L3` | root | task/WM operations the shell refused; reversible experiments | requires a recovery path |
| `L4` | ROM / platform signature | replacing SystemUI / Quickstep / WM Shell | not an app capability at all |

### The specific hidden APIs involved

| Operation | Mechanism | Evidence |
|---|---|---|
| windowing mode | `ActivityOptions.setLaunchWindowingMode` by reflection | `smartdock@517604a AppUtils.kt:375`; `Taskbar@e2a00b9 U.java:988` |
| true task list | `IActivityManager.getTasks` via `ShizukuBinderWrapper` | `smartdock ActivityManagerWrapper.kt` |
| task windowing mode | reflection on `RunningTaskInfo.getWindowingMode` | `smartdock AppUtils.kt:184` |
| task resize | `su` → `am task resize` | `smartdock AppUtils.kt:381` — **root**, not shell |
| density | `IWindowManager.setForcedDisplayDensityForUser` or `wm density` | `Taskbar U.java:1933` |
| density reset | `clearForcedDisplayDensityForUser` / `wm density reset` | `Taskbar U.java:1941` |

Note the asymmetry the sources prove: a shell bridge is enough for the task list
and density, but **resize needs root**. A single "privilege level" would hide
that, which is why the gate is per-operation (FIX-02).

## 3. Per-operation gating (FIX-02)

TZ v4 §8 states it plainly: notifications and overlay in no way prove the
ability to `resizeTask`. The previous implementation violated this — a shared
`highestTier` meant any L1 grant unlocked every L1 capability.

Each capability now declares one `RequiredGrant`, and `stateFor()` checks that
grant alone. Seven regression tests cover it, including that notification plus
overlay access leaves `RESIZE_TASK` and `SET_WINDOWING_MODE`
`PRIVILEGE_REQUIRED`, and that usage-stats access does **not** grant a true task
list.

## 4. Requirements any future implementation must meet

1. A separate adapter and explicit requests; minimum permissions.
2. Real `connect / check / execute / verify / disconnect`, where `verify` means
   re-measuring, not trusting a return value.
3. Per-operation gating — never a blanket "privileged" flag.
4. On permission revocation the UI keeps working; no crash loop.
   `CrashLoopGuard` already disables our own failing feature after two strikes
   and never touches system state.
5. For dangerous changes: record the original state **first**, be atomic where
   possible, provide rollback, and document ADB recovery.
6. **Root is never required** for Metro, the launcher or Focused Fullscreen.

## 5. Rollback

See `OP02_DISPLAY_RECOVERY.md`. The essential asymmetry: the shell records the
baseline before any attempt — including whether an override existed at all, since
restoring to "no override" is a different operation from writing a number back —
but at Tier 0 it cannot perform the write, so the restore is an operator action.

## 6. Status

| Item | Status |
|---|---|
| Interface and refusal | `TESTED` |
| Per-operation gate | `TESTED` |
| Shizuku connect/execute | `BLOCKED` — needs a device and the user's decision |
| Root bridge | `BLOCKED` — not attempted |
| L4 / SystemUI replacement | **refuted in writing** for app tiers, see `OP02_SYSTEMUI_WM_SHELL_FEASIBILITY.md` |
