# Display rollback — restoring your own configuration

**Read this before running the Tier 1 density experiment.**

The one thing this document guarantees: the information needed to undo the
change is written to disk *before* the change is attempted. It does **not**
promise an automatic undo. If the shell is force-stopped, or the device becomes
hard to read, you restore it yourself with the commands below.

## What the experiment actually does

It lowers the system display density. On a fixed-pixel panel that is the only
way to gain logical width:

```
effectiveWidthDp = widthPx * 160 / densityDpi
```

Lower `densityDpi` → more dp → **everything on screen gets physically smaller**.
There is no setting that makes a phone a tablet without that cost.

`wm density` affects the whole system, not just this shell.

## Before you start

Record your current state, including the case where you have no override:

```bash
adb shell wm density
```

```bash
adb shell wm size
```

`wm density` prints `Physical density: N`, and additionally
`Override density: M` **only if an override is already set**. Note which of the
two you saw — it decides how you restore.

The shell records the same thing in `DisplayExperimentState`
(`originalDensityDpi` plus `originalHadExplicitOverride`) through
`DisplayExperimentRepository.recordBaselineOnce`, which deliberately does not
overwrite an existing baseline: otherwise a second experiment would "restore"
to an already-overridden value.

## Applying a change

Take the target from the shell's diagnostics screen, which derives candidates
from *your* device's stock density rather than a hard-coded number:

```bash
adb shell wm density 288
```

Then reopen the shell's diagnostics screen. It re-measures and reports whether
the change was actually `OBSERVED`, rather than assuming it worked.

## Restoring

**If `wm density` showed no override line before you started** — you had no
override, so reset rather than writing a number back:

```bash
adb shell wm density reset
```

**If it did show `Override density: M`** — put that exact value back:

```bash
adb shell wm density M
```

Writing a "standard" number instead of yours is wrong. A number that looks
canonical for your model may not be what you had.

## If the screen becomes unusable

The display stays usable at the densities the shell offers, because they are
bounded by a readability guard at 62.5% of your stock density. If you went
lower by hand and cannot read the screen:

1. ADB still works regardless of how small the UI is — run the reset command
   above from your computer.
2. If ADB is unavailable, a reboot does **not** clear a `wm density` override;
   it persists. You need ADB, or Settings → Display if your ROM exposes a size
   control.
3. Safe mode does not clear it either.

This is exactly why the baseline is recorded before the change, and why the app
refuses to make the change itself.

## Why the app cannot do this for you

An ordinary APK holds no shell privileges. Specifically:

- `WRITE_SETTINGS` does **not** help. Display density is not a
  `WRITE_SETTINGS`-governed value.
- `WRITE_SECURE_SETTINGS` is a signature/privileged permission and cannot be
  granted to a normal install except over ADB.
- Reflecting into `IWindowManager` is a hidden API, is blocked on modern
  Android, and is not present in any shipped path of this project.

`NoPrivilegeDisplayBridge` therefore refuses every write and returns the exact
operator commands instead. `DisplayBridgeTest` asserts that it can never report
success.

## Scope

Restoring display configuration is **not** the same as restoring app data. This
shell does not and cannot save or restore the internal storage of arbitrary
third-party apps. Apps that get recreated by a configuration change manage their
own state.
