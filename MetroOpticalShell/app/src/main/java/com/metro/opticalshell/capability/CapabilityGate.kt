package com.metro.opticalshell.capability

import kotlinx.serialization.Serializable

/**
 * Privilege tiers (TZ §2). No layer may pretend to be another.
 *
 * The tier assignments below are not guesses: each is backed by a primary
 * source recorded in docs/OP02_UPSTREAM_RESEARCH.md.
 */
enum class PrivilegeTier {
    /** Ordinary app. Our own window, our own gestures, public APIs. */
    L0_APP,

    /** User-granted: Home role, notification listener, overlay, accessibility. */
    L1_USER_GRANTED,

    /** Shizuku / ADB bridge. Shell UID, not root. */
    L2_SHELL_BRIDGE,

    /** Root. */
    L3_ROOT,

    /** Platform signature / ROM. Not an app capability at all. */
    L4_PLATFORM,
}

/**
 * The state of one capability. The UI must always be able to say which of these
 * applies (TZ §2: "available / unavailable / privilege required / verified on
 * device").
 */
enum class CapabilityState {
    /** Usable right now. */
    AVAILABLE,

    /** The platform or OEM does not offer it here. */
    UNAVAILABLE,

    /** Possible, but needs a privilege the user has not granted. */
    PRIVILEGE_REQUIRED,

    /** Structurally impossible at this app's tier. Never becomes available. */
    IMPOSSIBLE_AT_APP_TIER,

    /** Not probed yet. */
    UNKNOWN,
}

/**
 * Whether a capability's *behaviour* has been seen on real hardware.
 *
 * Deliberately separate from [CapabilityState]: a capability can be AVAILABLE
 * (the API exists and returns success) while remaining DEVICE_UNVERIFIED (no
 * one has watched it actually work). TZ §14 forbids merging these.
 */
enum class VerificationLevel {
    /** Code exists. */
    IMPLEMENTED,

    /** An automated test covers it. */
    TESTED,

    /** Ran on an emulator. */
    EMULATOR_VERIFIED,

    /** Seen working on a physical device. */
    DEVICE_VERIFIED,
}

/** One gated capability. */
@Serializable
data class Capability(
    val id: String,
    val title: String,
    /** The lowest tier at which this is possible at all. */
    val requiredTier: String,
    val state: String,
    val verification: String,
    /** Why, in words fit to show a user. Never empty for a negative state. */
    val detail: String,
    /** Primary source that establishes the tier, where one exists. */
    val evidence: String? = null,
)

/**
 * The single place that answers "can we do X, and may we say we can?"
 * (TZ §2, §13).
 *
 * Design rules this type enforces:
 *
 *  1. Every capability declares its [PrivilegeTier]. A capability whose tier is
 *     above what the app holds can only ever be `PRIVILEGE_REQUIRED` or
 *     `IMPOSSIBLE_AT_APP_TIER` — [stateFor] cannot return `AVAILABLE` for it.
 *     That makes a false "available" a compile-and-test-level impossibility
 *     rather than a matter of discipline.
 *  2. `IMPOSSIBLE_AT_APP_TIER` is reserved for L4. Nothing a user grants or a
 *     bridge provides can lift it, so the UI must never offer a path.
 *  3. Verification is tracked separately and never upgraded by code.
 */
object CapabilityGate {

    /** Capability ids, so call sites cannot typo a string. */
    object Ids {
        const val LIST_APPS = "list_apps"
        const val LAUNCH_APP = "launch_app"
        const val DETECT_FREEFORM = "detect_freeform"
        const val REQUEST_LAUNCH_BOUNDS = "request_launch_bounds"
        const val SET_WINDOWING_MODE = "set_windowing_mode"
        const val RESIZE_TASK = "resize_task"
        const val LIST_RUNNING_TASKS = "list_running_tasks"
        const val INFER_APP_RECENCY = "infer_app_recency"
        const val SET_SYSTEM_DENSITY = "set_system_density"
        const val BE_RECENTS_PROVIDER = "be_recents_provider"
        const val BE_HOME = "be_home"
        const val READ_NOTIFICATIONS = "read_notifications"
        const val OPEN_SYSTEM_PANELS = "open_system_panels"
        const val DRAW_OVERLAY = "draw_overlay"
        const val REPLACE_STATUS_BAR = "replace_status_bar"
    }

    /**
     * The static tier of each capability, with its evidence.
     *
     * This table is the project's single source of truth about what is
     * reachable. It is derived from primary sources, not from optimism.
     */
    private val tiers: Map<String, TierFact> = mapOf(
        Ids.LIST_APPS to TierFact(
            PrivilegeTier.L0_APP,
            "LauncherApps plus a <queries> manifest declaration.",
            null,
        ),
        Ids.LAUNCH_APP to TierFact(
            PrivilegeTier.L0_APP,
            "Intent and PackageManager.",
            null,
        ),
        Ids.DETECT_FREEFORM to TierFact(
            PrivilegeTier.L0_APP,
            "FEATURE_FREEFORM_WINDOW_MANAGEMENT and Settings.Global " +
                "enable_freeform_support are both readable without privileges.",
            "farmerbb/Taskbar U.java:850 (e2a00b9)",
        ),
        Ids.REQUEST_LAUNCH_BOUNDS to TierFact(
            PrivilegeTier.L0_APP,
            "ActivityOptions.setLaunchBounds is public, but it is only a " +
                "preference. Android ignores it where freeform is off, so a " +
                "successful call is not a window.",
            "developer.android.com ActivityOptions",
        ),
        Ids.SET_WINDOWING_MODE to TierFact(
            PrivilegeTier.L2_SHELL_BRIDGE,
            "ActivityOptions.setLaunchWindowingMode is a hidden API reached by " +
                "reflection. Not public SDK.",
            "axel358/smartdock AppUtils.kt:375; farmerbb/Taskbar U.java:988",
        ),
        Ids.RESIZE_TASK to TierFact(
            PrivilegeTier.L3_ROOT,
            "Resizing an existing task shells out to `am task resize` as root.",
            "axel358/smartdock AppUtils.kt:381 (runAsRoot)",
        ),
        Ids.LIST_RUNNING_TASKS to TierFact(
            PrivilegeTier.L2_SHELL_BRIDGE,
            "A true task list needs IActivityManager through a Shizuku binder. " +
                "ActivityManager.getRunningTasks returns only our own tasks.",
            "axel358/smartdock ActivityManagerWrapper.kt",
        ),
        Ids.INFER_APP_RECENCY to TierFact(
            PrivilegeTier.L1_USER_GRANTED,
            "UsageStatsManager with PACKAGE_USAGE_STATS gives inferred recency. " +
                "This is NOT a list of live windows and must never be shown as one.",
            "axel358/smartdock AppUtils.getRecentTasks",
        ),
        Ids.SET_SYSTEM_DENSITY to TierFact(
            PrivilegeTier.L2_SHELL_BRIDGE,
            "Either `wm density` over ADB or the hidden " +
                "IWindowManager.setForcedDisplayDensityForUser. Both need " +
                "WRITE_SECURE_SETTINGS, which an ordinary install cannot hold. " +
                "WRITE_SETTINGS does not cover density.",
            "farmerbb/Taskbar U.java:1933",
        ),
        Ids.BE_RECENTS_PROVIDER to TierFact(
            PrivilegeTier.L4_PLATFORM,
            "TouchInteractionService is declared with STATUS_BAR_SERVICE and the " +
                "Quickstep manifest also requires MONITOR_INPUT, " +
                "MANAGE_ACTIVITY_TASKS and ALLOW_SLIPPERY_TOUCHES. These are " +
                "signature permissions. Handling swipes in our own window is a " +
                "different thing and is not this.",
            "AOSP Launcher3 quickstep/AndroidManifest.xml",
        ),
        Ids.BE_HOME to TierFact(
            PrivilegeTier.L1_USER_GRANTED,
            "The Home role is the user's choice. The OEM may still refuse it or " +
                "change navigation behaviour when it is granted.",
            "developer.android.com RoleManager",
        ),
        Ids.READ_NOTIFICATIONS to TierFact(
            PrivilegeTier.L1_USER_GRANTED,
            "NotificationListenerService after explicit, revocable consent.",
            "developer.android.com NotificationListenerService",
        ),
        Ids.OPEN_SYSTEM_PANELS to TierFact(
            PrivilegeTier.L1_USER_GRANTED,
            "An AccessibilityService can request the global NOTIFICATIONS, " +
                "QUICK_SETTINGS and RECENTS actions. That opens the system's own " +
                "panels; it does not grant SystemUI's powers.",
            "developer.android.com AccessibilityService",
        ),
        Ids.DRAW_OVERLAY to TierFact(
            PrivilegeTier.L1_USER_GRANTED,
            "SYSTEM_ALERT_WINDOW. Android 12+ blocks untrusted touch " +
                "pass-through, and an overlay must stay dismissible.",
            "developer.android.com behaviour-changes-12",
        ),
        Ids.REPLACE_STATUS_BAR to TierFact(
            PrivilegeTier.L4_PLATFORM,
            "Replacing the status bar or keyguard is not an app capability at any " +
                "grant level.",
            null,
        ),
    )

    private data class TierFact(
        val tier: PrivilegeTier,
        val detail: String,
        val evidence: String?,
    )

    /** What the app currently holds. */
    data class HeldPrivileges(
        val isHome: Boolean = false,
        val hasNotificationAccess: Boolean = false,
        val hasOverlayPermission: Boolean = false,
        val hasAccessibilityService: Boolean = false,
        val hasUsageStatsAccess: Boolean = false,
        val shellBridgeConnected: Boolean = false,
        val rootAvailable: Boolean = false,
    ) {
        /** The highest tier actually available. Never above L3 for any app. */
        val highestTier: PrivilegeTier
            get() = when {
                rootAvailable -> PrivilegeTier.L3_ROOT
                shellBridgeConnected -> PrivilegeTier.L2_SHELL_BRIDGE
                isHome || hasNotificationAccess || hasOverlayPermission ||
                    hasAccessibilityService || hasUsageStatsAccess -> PrivilegeTier.L1_USER_GRANTED
                else -> PrivilegeTier.L0_APP
            }
    }

    fun tierOf(capabilityId: String): PrivilegeTier =
        tiers[capabilityId]?.tier ?: PrivilegeTier.L4_PLATFORM

    fun detailOf(capabilityId: String): String =
        tiers[capabilityId]?.detail ?: "Unknown capability: no tier is recorded for it."

    fun evidenceOf(capabilityId: String): String? = tiers[capabilityId]?.evidence

    /**
     * The gate. Returns what may honestly be said about [capabilityId].
     *
     * [platformSupports] is the result of probing the device, where probing is
     * meaningful (freeform, for instance). Pass null when there is nothing to
     * probe. Note it can only ever *reduce* the answer: a platform that reports
     * support cannot raise a capability above its tier.
     */
    fun stateFor(
        capabilityId: String,
        held: HeldPrivileges,
        platformSupports: Boolean? = null,
    ): CapabilityState {
        val fact = tiers[capabilityId] ?: return CapabilityState.UNKNOWN

        // Rule 2: L4 is never reachable, whatever is granted.
        if (fact.tier == PrivilegeTier.L4_PLATFORM) {
            return CapabilityState.IMPOSSIBLE_AT_APP_TIER
        }

        // Rule 1: a capability above what we hold is never AVAILABLE.
        val holds = when (fact.tier) {
            PrivilegeTier.L0_APP -> true
            PrivilegeTier.L1_USER_GRANTED -> held.highestTier.ordinal >= PrivilegeTier.L1_USER_GRANTED.ordinal
            PrivilegeTier.L2_SHELL_BRIDGE -> held.shellBridgeConnected || held.rootAvailable
            PrivilegeTier.L3_ROOT -> held.rootAvailable
            PrivilegeTier.L4_PLATFORM -> false
        }
        if (!holds) return CapabilityState.PRIVILEGE_REQUIRED

        // The platform still gets the last word downwards.
        return when (platformSupports) {
            false -> CapabilityState.UNAVAILABLE
            else -> CapabilityState.AVAILABLE
        }
    }

    /** The full report, for the diagnostics screen and OP02_CAPABILITIES.json. */
    fun report(
        held: HeldPrivileges,
        platformProbes: Map<String, Boolean> = emptyMap(),
        verification: Map<String, VerificationLevel> = emptyMap(),
    ): List<Capability> = tiers.entries
        .sortedBy { it.value.tier.ordinal }
        .map { (id, fact) ->
            Capability(
                id = id,
                title = id.replace('_', ' '),
                requiredTier = fact.tier.name,
                state = stateFor(id, held, platformProbes[id]).name,
                verification = (verification[id] ?: VerificationLevel.IMPLEMENTED).name,
                detail = fact.detail,
                evidence = fact.evidence,
            )
        }

    /** Every capability that is structurally out of reach. */
    fun impossibleCapabilities(): List<String> =
        tiers.filterValues { it.tier == PrivilegeTier.L4_PLATFORM }.keys.sorted()
}
