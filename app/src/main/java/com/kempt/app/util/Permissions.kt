/**
 * @file
 * @brief Helpers for Kempt's special-access permissions and the Settings intents that grant them.
 */
package com.kempt.app.util

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.kempt.app.R
import com.kempt.app.monitor.KemptDeviceAdminReceiver

/**
 * @brief Central place for Kempt's special-access permission checks and grant intents.
 *
 * @details Kempt depends on three special-access permissions. Unlike normal runtime
 * permissions, none can be requested with an in-app dialog — the user must toggle each in a
 * dedicated Settings screen, so this object also exposes the intents that navigate there.
 *
 * @note @c object in Kotlin declares a singleton: there is exactly one @c Permissions
 * instance, and its members are accessed like statics (e.g. @c Permissions.hasUsageAccess(...)).
 */
object Permissions {

    /**
     * @brief Checks whether the app holds the "Usage access" special permission.
     *
     * @details Usage access lets Kempt query @c UsageStatsManager to learn which app is in the
     * foreground — the core signal the monitor watches. The check goes through
     * @c AppOpsManager; the exact API differs by OS level (@c unsafeCheckOpNoThrow on
     * Android 10+, the deprecated @c checkOpNoThrow below it).
     *
     * @param context Any context; used to resolve the AppOps service and the package name.
     * @return @c true when usage access is currently granted.
     */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * @brief Checks whether the app can draw over other apps (required for the lock overlay).
     * @param context Context used for the system check.
     * @return @c true when the "Draw over other apps" permission is granted.
     */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * @brief Builds an intent that opens the system "Usage access" settings list.
     * @return An @c Intent the caller can start to let the user grant usage access.
     */
    fun usageAccessSettings(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * @brief Builds an intent that opens this app's "Draw over other apps" settings page.
     * @param context Used to embed the app's package in the intent so Settings deep-links to Kempt.
     * @return An @c Intent the caller can start to let the user grant the overlay permission.
     */
    fun overlaySettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * @brief Builds an intent that opens the battery-optimization settings screen.
     * @details Exempting Kempt from battery optimization is optional but helps the monitor
     * survive Doze. This opens the general list rather than requesting the exemption directly.
     * @return An @c Intent the caller can start.
     */
    fun batteryOptimizationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * @brief Resolves the @c ComponentName of Kempt's device-admin receiver.
     * @details A @c ComponentName is the fully-qualified address (package + class) of a component;
     * the device-admin APIs identify our admin by it. Every device-admin call below routes through
     * this so the address is defined in exactly one place.
     * @param context Any context; used to resolve the package.
     * @return The component name of @ref com.kempt.app.monitor.KemptDeviceAdminReceiver.
     */
    fun deviceAdminComponent(context: Context): ComponentName =
        ComponentName(context, KemptDeviceAdminReceiver::class.java)

    /**
     * @brief Checks whether Kempt is currently an active device administrator (uninstall protection on).
     * @details While this is @c true, Android blocks Kempt's uninstall through the normal
     * launcher / Settings flow — the "friction" half of the uninstall-protection design.
     * @param context Any context; used to resolve the device-policy service.
     * @return @c true when Kempt's device admin is active.
     */
    fun isDeviceAdminActive(context: Context): Boolean =
        devicePolicyManager(context).isAdminActive(deviceAdminComponent(context))

    /**
     * @brief Builds the intent that opens the system "activate device admin?" consent screen for Kempt.
     * @details Device admin, like the other special-access grants, can't be turned on with an in-app
     * dialog — only the user can confirm it on a system screen. @c EXTRA_ADD_EXPLANATION supplies the
     * reassurance text shown there. Launch this for a result so the UI can re-check the state on return.
     * @param context Used to embed the admin component and read the explanation string.
     * @return An @c Intent the caller can start (ideally for a result).
     */
    fun addDeviceAdminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.uninstall_protection_explanation)
            )

    /**
     * @brief Turns uninstall protection off by removing Kempt's own device-admin registration.
     * @details An app may always deactivate its *own* admin with no extra permission. This is the
     * clean, in-app way to switch protection off (the system's "device admin" Settings screen is the
     * other way, and it's the one that triggers the tamper report). Deactivating fires
     * @ref com.kempt.app.monitor.KemptDeviceAdminReceiver.onDisabled, which logs the change.
     * Calling it when the admin isn't active is a harmless no-op.
     * @param context Any context; used to resolve the device-policy service.
     */
    fun removeDeviceAdmin(context: Context) {
        val dpm = devicePolicyManager(context)
        val component = deviceAdminComponent(context)
        if (dpm.isAdminActive(component)) dpm.removeActiveAdmin(component)
    }

    /**
     * @brief Resolves the system @c DevicePolicyManager.
     * @param context Any context.
     * @return The device-policy service used by the device-admin helpers above.
     */
    private fun devicePolicyManager(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
}
