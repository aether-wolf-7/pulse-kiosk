package br.com.pulsefitness.kiosk.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * All Device Owner policy in one place.
 *
 * The tablet is bolted next to a machine and used by the public, so the app
 * has to be the only thing reachable: no home button, no recents, no status
 * bar, no lock screen, and it comes back by itself after a power cut.
 *
 * Two rules govern everything here.
 *
 * 1. Degrade, never brick. If the app is NOT Device Owner (a dev build, or a
 *    tablet nobody provisioned) every call is skipped and the app still runs
 *    as an ordinary app, so a provisioning mistake shows up as "this tablet
 *    is not locked" instead of "this tablet is dead".
 * 2. Never remove our own way back in. ADB, safe mode and factory reset stay
 *    available on purpose: they are the recovery path if a build ever
 *    crash-loops while this app is the launcher. Locking those away turns a
 *    bad release into three tablets that need a firmware flash.
 */
object KioskManager {
    private const val TAG = "KioskManager"

    private const val PREFS = "kiosk_state"
    private const val KEY_MAINTENANCE_UNTIL = "maintenance_until_ms"

    /**
     * How long an unlock lasts. Long enough to change wifi or sideload a
     * build, short enough that a technician who forgets to re-lock does not
     * leave a public tablet wide open until someone notices.
     */
    private const val MAINTENANCE_WINDOW_MS = 15 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Maintenance has to outlive the Activity.
     *
     * Unlocking clears the HOME role, and the system reacts by restarting the
     * launcher, i.e. us. If "we are in maintenance" lived in the Activity, that
     * restart would lose it and onResume would immediately re-pin the screen
     * the technician just unlocked, trapping them in a loop. Persist it, with
     * an expiry so it cannot be left on by accident.
     */
    fun beginMaintenance(context: Context) {
        prefs(context).edit()
            .putLong(KEY_MAINTENANCE_UNTIL, System.currentTimeMillis() + MAINTENANCE_WINDOW_MS)
            .apply()
    }

    fun endMaintenance(context: Context) {
        prefs(context).edit().remove(KEY_MAINTENANCE_UNTIL).apply()
    }

    fun isMaintenanceActive(context: Context): Boolean {
        val until = prefs(context).getLong(KEY_MAINTENANCE_UNTIL, 0L)
        if (until == 0L) return false
        if (System.currentTimeMillis() >= until) {
            endMaintenance(context)
            return false
        }
        return true
    }

    /** AC | USB | WIRELESS: the tablet is wall-powered, keep it awake. */
    private val STAY_ON_MASK = (
        BatteryManager.BATTERY_PLUGGED_AC or
            BatteryManager.BATTERY_PLUGGED_USB or
            BatteryManager.BATTERY_PLUGGED_WIRELESS
        ).toString()

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) =
        ComponentName(context.applicationContext, KioskDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /**
     * Applies the persistent policy. Safe to call on every launch; all of it
     * is idempotent. Must run before [enterKiosk], because setLockTaskPackages
     * is what makes lock task permitted for us in the first place.
     */
    fun applyPolicy(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.i(TAG, "Not device owner, running unlocked (dev or unprovisioned tablet)")
            return
        }
        if (isMaintenanceActive(context)) {
            Log.i(TAG, "Maintenance window open, leaving the tablet unlocked")
            return
        }
        val dpm = dpm(context)
        val admin = admin(context)

        // Only this app may be pinned. Note this REPLACES the allowlist; if we
        // ever open a Custom Tab or another package it must be added here too.
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(context.packageName)) }
            .onFailure { Log.e(TAG, "setLockTaskPackages failed, kiosk will not lock", it) }

        // Persistent policy, not per-entry state, so it belongs here. Without
        // an explicit value the platform leaves LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        // on, which hands any gym member the power menu on a long press.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }.onFailure { Log.w(TAG, "setLockTaskFeatures failed", it) }
        }

        // Own the HOME role so a power cut boots straight back into the app and
        // Home goes nowhere. This is what actually restores the kiosk after a
        // reboot; no BOOT_COMPLETED receiver is required for it.
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        runCatching {
            dpm.addPersistentPreferredActivity(
                admin,
                homeFilter,
                ComponentName(context.packageName, "br.com.pulsefitness.kiosk.MainActivity"),
            )
        }.onFailure { Log.w(TAG, "addPersistentPreferredActivity failed", it) }

        // These two report failure by RETURNING FALSE rather than throwing.
        // setKeyguardDisabled returns false whenever a secure lock screen is
        // set, which is why the runbook insists the tablets stay on swipe.
        runCatching {
            if (!dpm.setStatusBarDisabled(admin, true)) {
                Log.w(TAG, "setStatusBarDisabled returned false")
            }
        }
        runCatching {
            if (!dpm.setKeyguardDisabled(admin, true)) {
                Log.w(TAG, "setKeyguardDisabled returned false: remove the screen lock")
            }
        }

        // Screen stays on while powered, which is always.
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_MASK)
        }.onFailure { Log.w(TAG, "stay-on setting failed", it) }

        // Keep ADB reachable for the whole pilot. This is the lifeline for
        // updating or rescuing a bolted-down tablet.
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")
        }.onFailure { Log.w(TAG, "keeping adb enabled failed", it) }

        applyUserRestrictions(dpm, admin)
    }

    /**
     * Restrictions that stop a student wandering somewhere they should not.
     *
     * Deliberately ABSENT, and they must stay absent:
     *  - DISALLOW_DEBUGGING_FEATURES kills ADB, our only remote lifeline.
     *  - DISALLOW_SAFE_BOOT removes safe mode, the way back when a launcher
     *    app crash-loops.
     *  - DISALLOW_FACTORY_RESET removes the last resort.
     *  - DISALLOW_INSTALL_APPS / DISALLOW_UNINSTALL_APPS block `adb install -r`
     *    of a fixed build and refuse uninstall, so a bad release could only be
     *    undone by wiping every tablet.
     */
    private fun applyUserRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        val restrictions = buildList {
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            add(UserManager.DISALLOW_CONFIG_TETHERING)
            add(UserManager.DISALLOW_CREATE_WINDOWS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }
        }
        restrictions.forEach { restriction ->
            runCatching { dpm.addUserRestriction(admin, restriction) }
                .onFailure { Log.w(TAG, "restriction $restriction failed", it) }
        }
    }

    /**
     * Enters Lock Task Mode. No-op when not Device Owner, and a no-op when
     * already locked: entering lock task itself causes a pause/resume, so an
     * unguarded call from onResume re-enters in a loop.
     */
    fun enterKiosk(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        if (isMaintenanceActive(activity)) return
        if (lockState(activity) != ActivityManager.LOCK_TASK_MODE_NONE) return
        if (!dpm(activity).isLockTaskPermitted(activity.packageName)) {
            // Calling startLockTask() anyway would drop us into the escapable
            // PINNED mode and look locked while not being locked.
            Log.e(TAG, "Lock task not permitted; provisioning is incomplete")
            return
        }
        runCatching { activity.startLockTask() }
            .onFailure { Log.e(TAG, "startLockTask failed, tablet is NOT locked", it) }
    }

    /**
     * Leaves Lock Task Mode for maintenance, handing back the status bar, the
     * lock screen and the Home button so staff can reach Settings.
     */
    fun exitKiosk(activity: Activity) {
        beginMaintenance(activity)
        if (lockState(activity) != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { activity.stopLockTask() }
                .onFailure { Log.w(TAG, "stopLockTask failed", it) }
        }
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        runCatching { dpm.setKeyguardDisabled(admin, false) }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName) }
    }

    private fun lockState(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return runCatching { am.lockTaskModeState }
            .getOrDefault(ActivityManager.LOCK_TASK_MODE_NONE)
    }

    /**
     * True only for real lock task. LOCK_TASK_MODE_PINNED is the degraded
     * screen-pinning fallback that anyone escapes with back + recents, so
     * treating it as "locked" would report a wide-open tablet as secure.
     */
    fun isLocked(activity: Activity): Boolean =
        lockState(activity) == ActivityManager.LOCK_TASK_MODE_LOCKED

    /**
     * Break glass: gives the tablet back completely.
     *
     * This has to exist in the very first build that becomes Device Owner.
     * `adb uninstall` refuses a Device Owner app and `dpm remove-active-admin`
     * refuses any admin that is not testOnly, so an app that cannot un-enrol
     * itself can only be removed by factory resetting the tablet.
     */
    fun releaseDeviceOwner(activity: Activity) {
        exitKiosk(activity)
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CREATE_WINDOWS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        ).forEach { runCatching { dpm.clearUserRestriction(admin, it) } }
        runCatching { dpm.clearDeviceOwnerApp(activity.packageName) }
            .onFailure { Log.e(TAG, "clearDeviceOwnerApp failed", it) }
    }
}
