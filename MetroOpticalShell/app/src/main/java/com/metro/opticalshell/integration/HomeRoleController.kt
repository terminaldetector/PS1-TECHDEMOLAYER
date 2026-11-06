package com.metro.opticalshell.integration

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * What the system currently thinks about Metro as a Home app (TZ v4 §7).
 *
 * These are reported separately on purpose. "The alias is enabled" is not "the
 * role is available", which is not "we are Home" — and conflating them is what
 * made the previous version claim a Home path it did not have (FIX-01).
 */
data class HomeStatus(
    /** The HomeAlias component is enabled, so Metro can appear as a candidate. */
    val candidateRegistered: Boolean,
    /** RoleManager reports ROLE_HOME exists and is available on this build. */
    val roleAvailable: Boolean,
    /** We currently hold the role / are the resolved Home app. */
    val isCurrentHome: Boolean,
    /** The package the system resolves ACTION_MAIN + CATEGORY_HOME to. */
    val resolvedHomePackage: String?,
    /** Every package the system considers a Home candidate. */
    val availableHomePackages: List<String>,
    /** Which build variant is installed, since debug and release differ (FIX-07). */
    val installedPackage: String,
    val apiLevel: Int,
    val manufacturer: String,
    val romHint: String?,
    /**
     * Recorded by an operator who saw the ROM refuse the selection. Null means
     * "not attempted"; the app cannot observe a Settings dialog.
     */
    val oemRefusedSelection: Boolean? = null,
) {
    /**
     * Honest verdict, in the vocabulary §7 asks for.
     */
    val verdict: HomeVerdict
        get() = when {
            isCurrentHome -> HomeVerdict.ROLE_GRANTED
            !candidateRegistered -> HomeVerdict.CANDIDATE_NOT_REGISTERED
            // The refusal happens inside Settings, so the app cannot detect it:
            // from here a refused selection and an un-chosen one look identical.
            // It is therefore an operator-recorded fact, like FreeformStatus.
            oemRefusedSelection == true -> HomeVerdict.OEM_REFUSED_SELECTION
            availableHomePackages.contains(installedPackage) -> HomeVerdict.CANDIDATE_VISIBLE
            roleAvailable -> HomeVerdict.ROLE_AVAILABLE_BUT_NOT_LISTED
            else -> HomeVerdict.UNKNOWN
        }

    /** The exact commands an operator can run to verify this independently. */
    /**
     * The documented shell-level route, for a ROM that refuses the selection in
     * Settings (HyperOS "only verified apps").
     *
     * `cmd package set-home-activity` is AOSP's own PackageManager shell
     * command. It sets the preferred Home activity directly and does not pass
     * through Settings' vendor policy check. It needs ADB (L2); it is not root,
     * and the shell cannot run it itself.
     *
     * UNVERIFIED on this ROM. A vendor may also block it at the package-manager
     * level, in which case the command fails and that is the real answer.
     */
    fun shellSetHomeCommands(): List<String> = listOf(
        "# make Metro the Home activity without the Settings policy check",
        "adb shell cmd package set-home-activity $installedPackage/com.metro.opticalshell.HomeAlias",
        "# confirm",
        "adb shell cmd package resolve-activity -a android.intent.action.MAIN " +
            "-c android.intent.category.HOME",
        "# revert to the stock launcher",
        "adb shell cmd package set-home-activity com.miui.home/com.miui.home.launcher.Launcher",
    )

    fun diagnosticCommands(): List<String> = listOf(
        "adb shell dumpsys package $installedPackage | grep -A3 HomeAlias",
        "adb shell cmd package resolve-activity -a android.intent.action.MAIN " +
            "-c android.intent.category.HOME",
        "adb shell dumpsys package preferred-activities | grep -i home",
        "adb shell pm get-home-activities",
    )

    fun asPlainText(): String = buildString {
        appendLine("installed package : $installedPackage")
        appendLine("api level         : $apiLevel")
        appendLine("manufacturer      : $manufacturer")
        appendLine("rom hint          : ${romHint ?: "none"}")
        appendLine("candidate enabled : $candidateRegistered")
        appendLine("role available    : $roleAvailable")
        appendLine("is current home   : $isCurrentHome")
        appendLine("resolved home     : ${resolvedHomePackage ?: "unknown"}")
        appendLine("home candidates   : ${availableHomePackages.joinToString(", ")}")
        appendLine("verdict           : ${verdict.name}")
        appendLine(verdict.message)
    }
}

enum class HomeVerdict {
    /** The alias is disabled, so the system cannot offer Metro at all. */
    CANDIDATE_NOT_REGISTERED,

    /** Metro is in the system's Home candidate list, awaiting the user's choice. */
    CANDIDATE_VISIBLE,

    /**
     * The role API exists but the system does not list Metro as a candidate.
     * This is the shape an OEM restriction takes, and it must be diagnosed
     * rather than blamed (§7).
     */
    ROLE_AVAILABLE_BUT_NOT_LISTED,

    /**
     * Metro IS listed as a candidate, but the OEM refuses to let it be
     * selected.
     *
     * DEVICE_OBSERVED on HyperOS: the Default Home screen list shows Metro, and
     * tapping it produces "As launcher apps from third parties may result in
     * problems like data leaks, abnormal battery drain, and lagging. Only
     * verified apps can be set as your default launcher."
     *
     * This is a Xiaomi policy check in Settings, not a missing permission and
     * not an Android limitation. The role API is untouched by it, which is why
     * the shell-level route below can still work where Settings refuses.
     */
    OEM_REFUSED_SELECTION,

    /** Metro is the Home app. */
    ROLE_GRANTED,

    UNKNOWN,
    ;

    val message: String
        get() = when (this) {
            CANDIDATE_NOT_REGISTERED ->
                "Metro is not registered as a Home candidate. Enable it first; " +
                    "that only makes it appear in the system's chooser, it does not " +
                    "make it Home."

            CANDIDATE_VISIBLE ->
                "Metro is offered in the system Home list. Choosing it is the user's " +
                    "decision, made in Android's own chooser."

            ROLE_AVAILABLE_BUT_NOT_LISTED ->
                "The Home role API exists but the system is not listing Metro as a " +
                    "candidate. Collect the diagnostics before concluding the OEM " +
                    "blocks it - a disabled component or a different installed build " +
                    "variant looks identical from here."

            OEM_REFUSED_SELECTION ->
                "This ROM refuses to let a third-party launcher be selected " +
                    "(\"only verified apps\"). Metro is correctly registered - the " +
                    "system lists it - so this is an OEM policy decision, not a " +
                    "fault in the shell. The documented shell-level route is " +
                    "`cmd package set-home-activity`, which does not go through " +
                    "the Settings check."

            ROLE_GRANTED ->
                "Metro is the current Home app. Check separately whether system " +
                    "gesture navigation survived: on some HyperOS builds assigning a " +
                    "third-party Home downgrades navigation to three buttons, and " +
                    "that would not count as a successful integration."

            UNKNOWN -> "The Home state could not be determined."
        }
}

/**
 * Makes Metro an *available* Home candidate and reports honestly what the
 * system does with it (TZ v4 §7, FIX-01).
 *
 * The design decision behind this class: enabling `HomeAlias` is **not** the
 * same as becoming Home. A component with a `HOME` intent filter merely appears
 * in Android's chooser; the system still requires the user to pick it. So the
 * alias can ship enabled without any risk of taking over Home silently, which
 * resolves FIX-01 — the previous build shipped it disabled and therefore never
 * appeared in the chooser at all, with no code anywhere to enable it.
 *
 * Nothing here selects Metro as Home. Only Android's own role request or
 * chooser can do that.
 */
class HomeRoleController(private val context: Context) {

    private val aliasComponent: ComponentName
        get() = ComponentName(context, "$HOME_ALIAS_PACKAGE.HomeAlias")

    fun status(): HomeStatus {
        val pm = context.packageManager
        val self = context.packageName

        val aliasEnabled = runCatching {
            when (pm.getComponentEnabledSetting(aliasComponent)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                // DEFAULT means "as declared in the manifest".
                else -> manifestAliasEnabled(pm)
            }
        }.getOrDefault(false)

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()

        val candidates = runCatching {
            pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())

        val roleAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val rm = context.getSystemService(RoleManager::class.java)
                rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)
            }.getOrDefault(false)
        } else {
            // Below Q there is no RoleManager; the chooser is the documented path.
            false
        }

        val heldRole = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.getSystemService(RoleManager::class.java)
                    ?.isRoleHeld(RoleManager.ROLE_HOME) == true
            }.getOrDefault(false)
        } else {
            resolved == self
        }

        return HomeStatus(
            candidateRegistered = aliasEnabled,
            roleAvailable = roleAvailable,
            isCurrentHome = heldRole || resolved == self,
            resolvedHomePackage = resolved,
            availableHomePackages = candidates,
            installedPackage = self,
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unknown",
            romHint = romHint(),
        )
    }

    private fun manifestAliasEnabled(pm: PackageManager): Boolean = runCatching {
        pm.getActivityInfo(aliasComponent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .enabled
    }.getOrDefault(false)

    /**
     * Registers Metro as a Home candidate. Requires an explicit user action at
     * the call site; this only makes the system *offer* Metro.
     */
    fun registerAsCandidate(): Boolean = setAliasEnabled(true)

    /** Withdraws the candidacy. The safe way back for a user who changed their mind. */
    fun unregisterAsCandidate(): Boolean = setAliasEnabled(false)

    private fun setAliasEnabled(enabled: Boolean): Boolean = runCatching {
        context.packageManager.setComponentEnabledSetting(
            aliasComponent,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        true
    }.getOrDefault(false)

    /**
     * The intent that asks the user for the Home role (API 29+).
     *
     * Returns null below Q, or when the role is unavailable, in which case
     * [systemHomeSettingsIntent] is the documented fallback.
     */
    fun roleRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val rm = context.getSystemService(RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(RoleManager.ROLE_HOME)) return null
            rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }.getOrNull()
    }

    /**
     * The documented fallback: the system's own Home-app settings screen.
     * Used below API 29 and whenever the role request is unavailable.
     */
    fun systemHomeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the user back to the stock launcher chooser (§12 "return to System launcher"). */
    fun restoreStockHomeIntent(): Intent = systemHomeSettingsIntent()

    private fun romHint(): String? = listOf(
        "ro.miui.ui.version.name",
        "ro.mi.os.version.name",
    ).firstNotNullOfOrNull { key ->
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val v = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            v.takeIf { it.isNotEmpty() }?.let { "$key=$it" }
        }.getOrNull()
    }

    companion object {
        const val HOME_ALIAS_PACKAGE = "com.metro.opticalshell"
    }
}
