package com.metro.opticalshell.panels

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** The outcome of asking the system to do something. */
sealed interface ActionResult {
    data object Opened : ActionResult
    data class Unavailable(val reason: String) : ActionResult
    data class ConsentRequired(val settingsIntent: Intent?, val reason: String) : ActionResult
}

/**
 * Opens the SYSTEM's own panels (TZ §6.2).
 *
 * What this is: a way to hand over to Android's notification shade, quick
 * settings and overview.
 *
 * What this is explicitly NOT: a replacement for any of them. An
 * AccessibilityService's global actions *invoke* system UI; they do not grant
 * SystemUI's powers, and they cannot render Recents or enumerate windows. TZ
 * §6.2 is clear that the two must not be confused, and
 * OP02_SYSTEMUI_WM_SHELL_FEASIBILITY.md records why the stronger thing is
 * impossible below L4.
 */
interface SystemPanelsAdapter {
    fun openNotifications(): ActionResult
    fun openQuickSettings(): ActionResult
    fun openRecents(): ActionResult
    fun isConnected(): Boolean
}

/**
 * The adapter that ships enabled: it can reach Settings screens with public
 * intents, and nothing else.
 *
 * It refuses the shade and overview rather than faking them, and points at the
 * accessibility consent flow that would enable them.
 */
class PublicIntentPanelsAdapter(private val context: Context) : SystemPanelsAdapter {

    override fun isConnected(): Boolean = false

    override fun openNotifications(): ActionResult = ActionResult.ConsentRequired(
        settingsIntent = accessibilitySettingsIntent(),
        reason = "Opening the notification shade needs an accessibility service, " +
            "which you must enable explicitly. No public API opens the shade.",
    )

    override fun openQuickSettings(): ActionResult = ActionResult.ConsentRequired(
        settingsIntent = accessibilitySettingsIntent(),
        reason = "Opening quick settings needs an accessibility service. Individual " +
            "settings screens can be opened directly without it.",
    )

    override fun openRecents(): ActionResult = ActionResult.ConsentRequired(
        settingsIntent = accessibilitySettingsIntent(),
        reason = "Opening the system overview needs an accessibility service. The " +
            "shell cannot draw its own Recents: that requires platform " +
            "permissions (STATUS_BAR_SERVICE, MONITOR_INPUT) it can never hold.",
    )

    /** Public and always available: a specific Settings page. */
    fun openSettingsPage(action: String): ActionResult = try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ActionResult.Opened
    } catch (e: android.content.ActivityNotFoundException) {
        ActionResult.Unavailable(e.message ?: "no activity for $action")
    }

    private fun accessibilitySettingsIntent(): Intent? = runCatching {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()
}

/**
 * The accessibility-backed adapter (TZ §6.2), active only once the user has
 * enabled the service.
 *
 * It uses exactly the four documented global actions and nothing more. It does
 * not simulate swipes, and it does not intercept input — TZ §7.2 forbids
 * synthesising navigation from touch simulation.
 */
class AccessibilityPanelsAdapter(
    private val serviceProvider: () -> AccessibilityService?,
) : SystemPanelsAdapter {

    override fun isConnected(): Boolean = serviceProvider() != null

    override fun openNotifications(): ActionResult =
        perform(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notification shade")

    override fun openQuickSettings(): ActionResult =
        perform(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "quick settings")

    override fun openRecents(): ActionResult =
        perform(AccessibilityService.GLOBAL_ACTION_RECENTS, "system overview")

    fun pressBack(): ActionResult =
        perform(AccessibilityService.GLOBAL_ACTION_BACK, "back")

    fun goHome(): ActionResult =
        perform(AccessibilityService.GLOBAL_ACTION_HOME, "home")

    private fun perform(action: Int, what: String): ActionResult {
        val service = serviceProvider()
            ?: return ActionResult.ConsentRequired(
                settingsIntent = null,
                reason = "The accessibility service is not connected, so the $what " +
                    "cannot be opened.",
            )
        return if (service.performGlobalAction(action)) {
            ActionResult.Opened
        } else {
            ActionResult.Unavailable(
                "The system refused the global action for the $what. This can " +
                    "happen on a secure screen, and is the system's decision.",
            )
        }
    }
}
