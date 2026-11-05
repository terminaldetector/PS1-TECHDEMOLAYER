# HyperOS / MIUI limitations

**Status: written from Android platform documentation and the shell's own
capability model. Nothing here has been confirmed on a HyperOS device in
Operation 01.** Where a claim is a platform fact it is marked as such; where it
is a widely-reported OEM behaviour that we have not verified, it says so.

The governing rule (TZ 5.3): **capability is determined by probing, never by the
ROM's name.** `SystemCapabilityProbe` does read MIUI/HyperOS build properties,
but only to label the diagnostics output. No branch in this project changes
behaviour based on that flag.

## Platform facts (not OEM-specific)

These are true on all current Android versions and are the reason several TZ
goals are bounded:

| Fact | Consequence |
|---|---|
| Display density is not a `WRITE_SETTINGS` value. | A normal APK cannot change it, with or without that permission. |
| `WRITE_SECURE_SETTINGS` is signature/privileged. | Cannot be held by a normal install except via ADB grant. |
| `ActivityOptions.setLaunchBounds()` sets a *preference*. | Android ignores it where freeform is unsupported. It does not enable freeform. |
| `android:resizeableActivity="true"` declares compatibility. | It does not switch on any windowing mode. |
| Enumerating running tasks needs Launcher/Recents privileges. | A normal shell cannot list other apps' live windows. |
| Package visibility is restricted on Android 11+. | A launcher needs `<queries>` for MAIN/LAUNCHER, or the restricted `QUERY_ALL_PACKAGES`. |

Each of these is honoured in code rather than worked around: see
`SystemDisplayBridge`, `FreeformLaunchAdapter`, `ExpandedWorkspaceState.recentShellLaunches`
and the manifest's `<queries>` element.

## OEM behaviours to expect (UNVERIFIED on device)

Reported of MIUI/HyperOS but **not confirmed here**. Treat each as a hypothesis
to test, and record the result in [DEVICE_TEST_MATRIX.md](DEVICE_TEST_MATRIX.md):

1. **Home role.** Xiaomi ROMs have historically added extra confirmation steps
   for third-party launchers, and some builds restrict the role or reset it
   after updates. The shell is built so this does not matter: it runs from a
   normal icon, and `HomeAlias` is `enabled="false"` in the manifest and is only
   ever enabled by explicit user action.
2. **Freeform.** MIUI has its own floating-window implementation that is not the
   same as AOSP freeform, and `FEATURE_FREEFORM_WINDOW_MANAGEMENT` may be absent
   even where a Xiaomi-specific small-window feature exists. The probe reports
   what the platform API says; where the answer is "unsupported", the shell
   launches full-screen and the desktop keeps working as a navigation shell.
3. **Background restrictions.** MIUI is aggressive about background work. This
   project deliberately has **no** background service: the clock and live tiles
   are composition-scoped and stop when the shell is not visible, so there is
   nothing for the ROM to kill.
4. **Density overrides.** HyperOS exposes its own display-size control, which
   may itself set an override. This is exactly why
   `DisplayExperimentRepository.recordBaselineOnce` records whether an override
   *already existed* and never overwrites a baseline — restoring must return the
   user to their own configuration, not to a factory number.

## Separating OEM limits from our own bugs

When something does not work on a HyperOS device, the diagnostics screen is the
first stop. It distinguishes the cases:

- The shell reports measured geometry that disagrees with what you see → our
  bug.
- The shell reports `UNSUPPORTED` from a platform API → OEM/platform limit.
- The shell reports `REQUESTED` but no window appeared → platform ignored the
  hint, which is documented behaviour, not a failure of the call.
- Density change reports `NOT_OBSERVED` after an ADB command succeeded → the ROM
  overrode or reverted it; record it.

A compile success proves nothing about any of this. Per TZ 13, nothing in this
project is marked `DEVICE_VERIFIED` until it has been watched working on
hardware.
