package com.metro.opticalshell.panels

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One notification, reduced to what a tile badge needs (TZ §3.3, §6.1).
 *
 * Deliberately narrow: the package, a count and an optional short title. The
 * shell does not store, aggregate or transmit notification bodies. TZ §12
 * forbids processing notification content off the device, and TZ §3.3 forbids
 * claiming to read other apps' private data.
 */
data class NotificationSummary(
    val packageName: String,
    val count: Int,
    val lastTitle: String? = null,
)

/**
 * Notification access, entirely consent-gated (TZ §6.1).
 *
 * Three rules this type enforces:
 *
 *  1. Nothing is read until the user has granted access in Settings, and
 *     [isGranted] is checked against the live system setting rather than a
 *     cached flag — the user may revoke it at any time.
 *  2. On revocation the cache is cleared, so a stale badge cannot outlive the
 *     permission.
 *  3. With no access, badges are **absent**, never zero-or-invented. TZ §3.3:
 *     "do not imitate the presence of unread events".
 */
object NotificationAccess {

    fun isGranted(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
        }.getOrNull() ?: return false
        val component = ComponentName(context, MetroNotificationListener::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    /** The Settings screen where the user grants or revokes access. */
    fun consentIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Why we would like it, in plain words, for the consent prompt. */
    const val RATIONALE: String =
        "Metro can show unread counts on tiles, the way Windows Phone did. That " +
            "needs notification access. The shell reads only the originating app " +
            "and a count; it does not store message contents and sends nothing off " +
            "the device. You can revoke this at any time in Settings, and badges " +
            "then disappear rather than freeze."
}

/**
 * The listener service. Registered in the manifest but inert until the user
 * grants access — Android will simply not bind it otherwise.
 */
class MetroNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected.value = true
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Rule 2: no stale badges once access is gone.
        connected.value = false
        summaries.value = emptyMap()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val grouped = active
            .filterNot { it.isOngoing }
            .groupBy { it.packageName }
        summaries.value = grouped.mapValues { (pkg, list) ->
            NotificationSummary(
                packageName = pkg,
                count = list.size,
                lastTitle = null, // Titles are deliberately not retained.
            )
        }
    }

    companion object {
        private val summaries = MutableStateFlow<Map<String, NotificationSummary>>(emptyMap())
        private val connected = MutableStateFlow(false)

        /** Badge data, empty whenever access is absent. */
        val notifications: StateFlow<Map<String, NotificationSummary>> = summaries.asStateFlow()
        val isConnected: StateFlow<Boolean> = connected.asStateFlow()

        /**
         * The badge for a package, or null when unknown.
         *
         * Rule 3: null means "we do not know", which the UI must render as no
         * badge at all rather than as a zero.
         */
        fun badgeFor(packageName: String): Int? =
            if (!connected.value) null else summaries.value[packageName]?.count
    }
}
