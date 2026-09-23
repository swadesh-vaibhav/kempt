/**
 * @file
 * @brief Helpers for Kempt's special-access permissions and the Settings intents that grant them.
 */
package com.kempt.app.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

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
}
