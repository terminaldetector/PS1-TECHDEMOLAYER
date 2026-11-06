# System integration matrix

`function → API/permission → implementation → status on device`

Status vocabulary from TZ v4 §13, kept strictly separate:
`SOURCE_IMPLEMENTED` · `TESTED` (automated) · `SYSTEM_REQUESTED` (we asked the
system) · `DEVICE_OBSERVED` (seen working on hardware) · `FAILED` · `BLOCKED`.

**No row is `DEVICE_OBSERVED`.** No Android device or emulator has been
available in any session of this project.

| Function | API / permission | Implementation | Status |
|---|---|---|---|
| List launchable apps | `LauncherApps`, `<queries>` | `InstalledAppsRepository` | `TESTED` |
| Launch an app | `Intent`, `PackageManager` | `AppLaunchOrchestrator` → `StandardAppLauncher` | `SOURCE_IMPLEMENTED` |
| **Single launch path** | — | `AppLaunchOrchestrator`, every surface routed (FIX-03) | `TESTED` |
| Landscape policy per app | — | `LandscapePolicyEngine.decide`, now actually called | `TESTED` |
| Freeform capability probe | `FEATURE_FREEFORM_WINDOW_MANAGEMENT`, `Settings.Global enable_freeform_support` | `FreeformLaunchAdapter.probe` | `TESTED` |
| Windowed launch request | `ActivityOptions.setLaunchBounds` (public, a hint) | `FreeformLaunchAdapter.launchWindowed` | `SOURCE_IMPLEMENTED` |
| Set a windowing mode | `ActivityOptions.setLaunchWindowingMode` (**hidden**) | gate only; no implementation | `BLOCKED` (needs L2) |
| Resize an existing task | `am task resize` as root | gate only | `BLOCKED` (needs L3) |
| True running-task list | `IActivityManager` via Shizuku | `AbsentShellBridge` refuses | `BLOCKED` (needs L2) |
| Inferred app recency | `UsageStatsManager` + `PACKAGE_USAGE_STATS` | `TaskSource.OBSERVED_RECENCY` | `SOURCE_IMPLEMENTED` |
| **Home candidate registration** | manifest alias + `setComponentEnabledSetting` | `HomeRoleController`, alias now ships **enabled** (FIX-01) | `SOURCE_IMPLEMENTED` |
| Home role request | `RoleManager.ROLE_HOME` (API 29+) | `HomeRoleController.roleRequestIntent` | `SOURCE_IMPLEMENTED` |
| Home fallback below API 29 | `Settings.ACTION_HOME_SETTINGS` | `HomeRoleController.systemHomeSettingsIntent` | `SOURCE_IMPLEMENTED` |
| Home status diagnosis | `PackageManager.resolveActivity`, `queryIntentActivities`, `getComponentEnabledSetting`, `RoleManager.isRoleAvailable/isRoleHeld` | `HomeRoleController.status` | `SOURCE_IMPLEMENTED` |
| Read notifications | `NotificationListenerService` | `MetroNotificationListener`, consent-gated | `SOURCE_IMPLEMENTED` |
| Open system shade / QS / overview | `AccessibilityService` global actions | `AccessibilityPanelsAdapter` (service **not registered**) | `BLOCKED` (needs L1 + registration) |
| Open a Settings page | public intents | `PublicIntentPanelsAdapter` | `SOURCE_IMPLEMENTED` |
| Gesture exclusion | `View.setSystemGestureExclusionRects` (API 29+) | `GestureExclusionEffect`, API-guarded | `TESTED` |
| Live gesture geometry | `WindowInsets.systemGestures` / `mandatorySystemGestures` / `ime`, `LocalConfiguration` | `rememberGestureGeometry` (FIX-06) | `TESTED` |
| Predictive back | AndroidX `BackHandler` | `ShellBackHandler` | `SOURCE_IMPLEMENTED` |
| Per-operation capability gate | — | `CapabilityGate` with `RequiredGrant` (FIX-02) | `TESTED` |
| Live permission read | `Settings.canDrawOverlays`, `AppOpsManager`, `RoleManager`, notification listeners setting | `MainActivity.heldPrivileges`, each read independently | `SOURCE_IMPLEMENTED` |
| Density change | `wm density` / hidden `IWindowManager` | Tier 0 bridge **refuses**, returns the operator procedure | `TESTED` |
| Density preview | pure arithmetic | `DisplayProfileManager.preview` | `TESTED` |
| Density rollback record | DataStore | `DisplayExperimentRepository.recordBaselineOnce` | `TESTED` |
| Replace status bar / be Recents provider | platform signature | not attempted; refuted in writing | `BLOCKED` (L4) |

## What the fixes changed

| Fix | Defect | Resolution |
|---|---|---|
| FIX-01 | `HomeAlias` was `enabled="false"` with no code anywhere to enable it, so Metro could never appear in the Home chooser | Alias ships **enabled** — which only makes it a *candidate*, since Android still requires the user to choose — plus `HomeRoleController` for role request, withdrawal and independent diagnosis |
| FIX-02 | A shared `highestTier` meant **any** L1 grant unlocked **every** L1 capability | Each capability declares one `RequiredGrant`, checked on its own; 7 regression tests |
| FIX-03 | `ShellViewModel.launch()` called the launcher directly, so `LandscapePolicyEngine.decide()` was **dead code** | Single `AppLaunchOrchestrator` implementing §4.3's seven steps; a bypass is recorded and surfaced in diagnostics |
| FIX-04 | `probe()` returned `REQUESTED` from capability flags alone, before anything was requested | New `CAPABLE` state; `REQUESTED` only after `launchWindowed` actually asks |
| FIX-05 | Any `recreatedOnRotate` classified an app as unstable, though recreation is **normal Android behaviour** | Instability now needs a real signal: lost state, crash, broken geometry, lost input or a restart loop |
| FIX-06 | Geometry was `remember`ed on the `View` instance, which is identical across a rotation, so it went **stale** exactly when it mattered | All values come from state-backed Compose sources that recompose on rotation, IME, density and resize |
| FIX-07 | debug and release carry different `applicationId`s, inviting false conclusions | `HomeStatus.installedPackage` reports the actual installed package in every diagnosis |

Two further real bugs were caught by lint while making these changes, both the
same class of error: `setSystemGestureExclusionRects` (API 29) and
`AppOpsManager.unsafeCheckOpNoThrow` (API 29) were called unguarded against
`minSdk 26`, and would have crashed on Android 8–9. Both are now guarded.
