# OP02 feasibility: replacing Recents / SystemUI / WM Shell

**Verdict: not feasible from an application, at any user-grantable privilege
level. Settled from primary sources, not estimated.**

TZ §6.4 scopes this as a research-only branch. This is that study. Nothing here
was installed, patched or attempted on a device.

## 1. What the platform requires

AOSP `Launcher3`, `quickstep/AndroidManifest.xml` (`refs/heads/main`, read
2026-11-05):

```xml
<service android:name="com.android.quickstep.TouchInteractionService"
     android:permission="android.permission.STATUS_BAR_SERVICE">
    <intent-filter>
        <action android:name="android.intent.action.QUICKSTEP_SERVICE"/>
    </intent-filter>
</service>
```

The same manifest requires `START_TASKS_FROM_RECENTS`, `REMOVE_TASKS`,
`MANAGE_ACTIVITY_TASKS`, `INTERNAL_SYSTEM_WINDOW`, `STATUS_BAR`,
`STATUS_BAR_SERVICE`, `STOP_APP_SWITCHES`, `SET_ORIENTATION`,
`READ_FRAME_BUFFER`, `MONITOR_INPUT`, `ALLOW_SLIPPERY_TOUCHES`,
`CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`, `WRITE_SECURE_SETTINGS`,
`SYSTEM_APPLICATION_OVERLAY`.

Two of these decide the question:

- **`MONITOR_INPUT`** — observe input events globally, including those that
  begin on another app's surface.
- **`ALLOW_SLIPPERY_TOUCHES`** — let a touch sequence transfer between windows
  mid-gesture.

A system gesture *starts on another app's window*. Without those two, a
third-party app cannot see it, let alone take it over. Both are signature-level.

## 2. Why the usual workarounds do not close the gap

| Approach | Why it fails |
|---|---|
| Compose gesture handlers | Only receive events inside our own window. Fine for Start↔AppList; irrelevant to system navigation. |
| `SYSTEM_ALERT_WINDOW` overlay | Does not grant `MONITOR_INPUT`. Android 12+ blocks untrusted touch pass-through, and a full-screen touch interceptor is precisely what TZ §7.2 forbids. |
| `AccessibilityService` global actions | Can *invoke* `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS` — opening the system's own UI. It cannot render Recents, enumerate windows, or claim the gesture. Useful and legitimate; not a replacement. |
| Shizuku (L2) | Shell UID, not root, not platform signature. It can reach `IActivityManager` (hence a true task list) but cannot register a `QUICKSTEP_SERVICE` provider, which the platform binds by signature permission. |
| Root (L3) | Could modify the system, but the binding is still signature-checked; this is the territory of ROM patching. |
| ROM / platform (L4) | The only level where it is real — and by definition not an app. |

## 3. What Lawnchair's QuickSwitch actually demonstrates

The spec points at QuickSwitch as the example. Not inspected directly this
operation; its documented design — a root module that makes the system bind a
third-party launcher as the Recents provider — is consistent with §1: the
binding must be changed at the system level because it cannot be obtained by an
app. It is evidence *for* this verdict, not a route around it.

## 4. The HyperOS-specific risk

The spec reports confirmed cases of HyperOS switching to 3-button navigation
when a third-party Home is set. If so, the trade is stark:

- keep the stock launcher → keep Xiaomi's gestures, Metro runs as a normal app;
- take the Home role → Metro becomes Home, and gestures may be downgraded.

TZ §7.3 calls this a **blocking condition**, and it is right to. The shell
therefore keeps both paths and must *report* which it is in rather than mask it.
This is unverified here — it needs the device.

## 5. What we will build instead

| Instead of | We do | Tier |
|---|---|---|
| Replacing Recents | A Recents **button** that opens the system's own overview | L1 |
| Replacing the shade | Our own Action Center for *our* surfaces, plus a documented hand-off to the system shade | L0 + L1 |
| A fake window list | `pinned` / `shell-launched` now; `observed recency` only with consent, labelled as inference; a true task list only behind a connected L2 bridge | L0/L1/L2 |
| Claiming gestures | Our swipes inside our window, with `systemGestureExclusion` used only within permitted limits | L0 |

## 6. If the privileged branch is ever pursued

Preconditions, none of which are met today:

1. A second device, not the user's daily driver.
2. A full image backup and a verified recovery path before any change.
3. A device-specific map of AOSP SystemUI ↔ WM Shell ↔ Xiaomi modules.
4. An OTA compatibility matrix — a system patch breaks on every update.
5. Reboot and permission-loss testing, with the device still usable after each.

Until all five exist, this stays a document. Per TZ §13, a pretty desktop with
no working system windows is not `complete`.
