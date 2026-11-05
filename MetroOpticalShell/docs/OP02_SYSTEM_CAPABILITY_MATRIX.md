# OP02 system capability matrix

Two columns that must never be merged: what the **platform** allows, and what
**this device** does. The first is settled from primary sources (see
`OP02_UPSTREAM_RESEARCH.md`). The second is **empty** — no Android device was
available in this operation.

`L0`–`L4` are the privilege tiers of TZ §2.

## Platform-level findings (evidence-backed)

| # | Capability | Tier needed | Mechanism | Evidence | Reachable at L0? |
|---|---|---|---|---|---|
| 1 | List launchable apps | L0 | `LauncherApps`, `<queries>` | implemented OP01 | ✅ |
| 2 | Launch an app | L0 | `Intent` + `PackageManager` | implemented OP01 | ✅ |
| 3 | Detect freeform support | L0 | `FEATURE_FREEFORM_WINDOW_MANAGEMENT`, `Settings.Global enable_freeform_support` | Taskbar `U.java:850` | ✅ |
| 4 | Request launch bounds | L0 | `ActivityOptions.setLaunchBounds` | public API; a hint only | ✅ (hint) |
| 5 | **Set a window's windowing mode** | **L2+** | `ActivityOptions.setLaunchWindowingMode` **hidden**, via reflection | Smart Dock `AppUtils.kt:375`, Taskbar `U.java:988` | ❌ |
| 6 | **Resize an existing task** | **L3 (root)** | `su` → `am task resize` | Smart Dock `AppUtils.kt:381` | ❌ |
| 7 | **True list of running tasks** | **L2 (Shizuku)** | `IActivityManager.getTasks` via `ShizukuBinderWrapper` | Smart Dock `ActivityManagerWrapper.kt` | ❌ |
| 8 | Each task's windowing mode | L2 | reflection on `RunningTaskInfo.getWindowingMode` | Smart Dock `AppUtils.kt:184` | ❌ |
| 9 | Inferred app recency | L1 | `UsageStatsManager.queryUsageStats` + `PACKAGE_USAGE_STATS` | Smart Dock `getRecentTasks` | ⚠️ L1, and **not** a window list |
| 10 | **Change system display density** | **L2/L3** | `IWindowManager.setForcedDisplayDensityForUser` (hidden) or `wm density`; needs `WRITE_SECURE_SETTINGS` | Taskbar `U.java:1933`; OP01 `SystemDisplayBridge` | ❌ |
| 11 | Reset density to *no* override | L2/L3 | `clearForcedDisplayDensityForUser` / `wm density reset` | Taskbar `U.java:1941` | ❌ |
| 12 | **Be the Recents/gesture provider** | **L4 (platform signature)** | `TouchInteractionService` requires `STATUS_BAR_SERVICE`, `MONITOR_INPUT`, `MANAGE_ACTIVITY_TASKS`, `ALLOW_SLIPPERY_TOUCHES` | AOSP `quickstep/AndroidManifest.xml` | ❌ |
| 13 | Be the Home app | L1 | `RoleManager` / user choice | OP01 `HomeAlias`, ships disabled | ✅ with consent |
| 14 | Read notifications | L1 | `NotificationListenerService` | platform API | ✅ with consent |
| 15 | Open the system notification shade / QS | L1 | `AccessibilityService` global actions | platform API | ✅ with consent |
| 16 | Draw an overlay over other apps | L1 | `SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY` | platform API | ✅ with consent |
| 17 | **Replace the status bar / keyguard** | **L4** | — | not an app capability | ❌ |
| 18 | Observe own window geometry | L0 | `WindowMetricsCalculator`, insets | implemented OP01 | ✅ |

### The five things this matrix forbids us from claiming

1. We cannot put a third-party app in a window — only *ask* (#4 vs #5).
2. We cannot resize someone else's task without root (#6).
3. We cannot list running windows; at L1 we can only infer recency (#7, #9).
4. We cannot change density ourselves (#10).
5. We cannot become the Recents/gesture provider at all below L4 (#12).

## Device-level findings

**Status: `NOT COLLECTED`.** No physical device or emulator was available.

| Field | Value |
|---|---|
| Model / SoC | _not collected_ |
| Android API / HyperOS build | _not collected_ |
| Panel px / physical density / override density | _not collected_ |
| `smallestScreenWidthDp` | _not collected_ |
| Navigation mode (gesture / 3-button) | _not collected_ |
| Current Home app | _not collected_ |
| `FEATURE_FREEFORM_WINDOW_MANAGEMENT` | _not collected_ |
| `enable_freeform_support` | _not collected_ |
| Mouse / keyboard attached | _not collected_ |

The shell's own diagnostics screen collects every one of these on first launch.

## §1.1 research questions

| Question | Answer | Basis |
|---|---|---|
| Can HyperOS assign a third-party Home and keep Back/Home/Recents gestures? | **UNKNOWN — device required.** The platform allows the Home role (#13); whether Xiaomi keeps gesture nav is an OEM behaviour and the spec itself reports confirmed cases of forced 3-button. | needs device |
| Who is the Recents/Quickstep provider, and can we be it? | **We cannot be it.** Settled at platform level: `STATUS_BAR_SERVICE` + `MONITOR_INPUT`. Which component *is* it on this ROM needs the device. | AOSP manifest ✅ |
| Is there real freeform on the internal display? | **UNKNOWN — device required.** The two signals to read are settled and already implemented. | Taskbar ✅ / needs device |
| Can we reach ≥600dp and get real tablet layouts? | **Geometry settled, app behaviour unknown.** OP01 computed: 600dp reachable on a 1080p/440dpi panel at 288dpi (~65% size); 840dp only below the guard. Which apps respond is per-app and needs the device. | OP01 `DisplayMathTest` ✅ |
| What breaks on rotation? | **UNKNOWN — device required.** This is per-app; `AppLayoutProbe` is built to record it. | needs device |
| What are SystemUI/overlay limits? | Partly settled: overlays cannot replace the status bar or keyguard (#17); Android 12+ blocks untrusted touch pass-through. OEM specifics need the device. | platform docs ✅ |
| Which mechanisms are public / Shizuku-ADB / root-only? | **Fully settled** — rows #1–#18 above. | ✅ |

Four of seven are answered with evidence. Three need hardware, and are named
rather than guessed.
