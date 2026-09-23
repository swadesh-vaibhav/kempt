/**
 * @file
 * @brief Device-admin receiver that provides uninstall protection and reports removal attempts.
 */
package com.kempt.app.monitor

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import com.kempt.app.R
import com.kempt.app.data.BlockEvent
import com.kempt.app.data.BlockEventDao
import com.kempt.app.sync.AccountabilityService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * @brief Kempt's device administrator: the "uninstall protection" layer, and a source of tamper signals.
 *
 * @details Registering as a device admin (via @ref com.kempt.app.util.Permissions.addDeviceAdminIntent)
 * makes Android refuse Kempt's uninstall through the normal launcher / Settings flow while the admin
 * is active. This is *friction, not a wall* — the device owner can still deactivate the admin and then
 * uninstall — but the design goal (see @c README.md) is that every such bypass becomes a **signal to
 * the accountability partner** rather than a silent escape. This receiver turns the device-admin
 * lifecycle callbacks into @ref com.kempt.app.data.BlockEvent rows that flow to the backend.
 *
 * The declared policy set (@c res/xml/device_admin.xml) is empty on purpose: Kempt relies only on the
 * uninstall-block behaviour and requests none of the invasive admin powers (wipe, force-lock, ...).
 *
 * @note A @c DeviceAdminReceiver is a specialised @c BroadcastReceiver. Android delivers the
 * lifecycle actions (enabled / disable-requested / disabled) to it, and the base class dispatches
 * them to the @c onXxx overrides below. Because it's framework-owned, Hilt can't constructor-inject
 * it; we pull dependencies through a @ref Deps @c @EntryPoint, exactly as @ref BootReceiver does.
 */
class KemptDeviceAdminReceiver : DeviceAdminReceiver() {

    /** @brief Hilt entry point exposing the dependencies the callbacks need. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        /** @brief @return The event DAO. */
        fun blockEventDao(): BlockEventDao

        /** @brief @return The accountability channel. */
        fun accountability(): AccountabilityService
    }

    /**
     * @brief Called when the user activates Kempt as a device admin — uninstall protection is now on.
     * @param context The receiver context.
     * @param intent The admin-enabled broadcast (unused).
     */
    override fun onEnabled(context: Context, intent: Intent) {
        // goAsync() keeps this short-lived receiver alive so the coroutine can finish its DB/network
        // work; the returned handle is finished in record()'s finally block. Safe here because these
        // actions carry no result the base class needs to hand back.
        record(context, BlockEvent.DEVICE_ADMIN_ENABLED, goAsync())
    }

    /**
     * @brief Called when the user asks (via Settings) to deactivate the admin — a tamper signal.
     *
     * @details Deactivating the admin is the required first step to uninstall Kempt, so a removal
     * *request* is exactly the "someone is trying to break out" moment we want the partner to hear
     * about. We record the signal and return a warning string the system shows on its confirmation
     * dialog.
     *
     * @note Unlike the other two callbacks, this one must NOT call @c goAsync(): the base
     * @c DeviceAdminReceiver reads the warning back through the receiver's still-pending result
     * (@c getResultExtras) *after* this method returns, and @c goAsync() would hand that pending
     * result away and make the base class throw. So the event is recorded fire-and-forget (a fast
     * local insert) while the warning is returned synchronously.
     *
     * @param context The receiver context.
     * @param intent The disable-requested broadcast (unused).
     * @return The warning text shown to the user before the admin is deactivated.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        record(context, BlockEvent.DEVICE_ADMIN_DISABLE_REQUESTED, pending = null)
        return context.getString(R.string.uninstall_protection_disable_warning)
    }

    /**
     * @brief Called once the admin has actually been deactivated — uninstall protection is now off.
     * @details Fires both for a user-driven deactivation in Settings (after @ref onDisableRequested)
     * and for Kempt's own in-app "Turn off" (@ref com.kempt.app.util.Permissions.removeDeviceAdmin),
     * so the partner sees whenever protection drops, however it happened.
     * @param context The receiver context.
     * @param intent The disabled broadcast (unused).
     */
    override fun onDisabled(context: Context, intent: Intent) {
        record(context, BlockEvent.DEVICE_ADMIN_DISABLED, goAsync())
    }

    /**
     * @brief Records an accountability event locally, reports it, and marks it synced if the report lands.
     *
     * @details Mirrors the record-and-report helpers elsewhere in the app. Runs on a background
     * coroutine because Room and the network client are @c suspend / off-main-thread.
     *
     * @param context Any context; the application context is used to resolve the Hilt entry point.
     * @param type One of the device-admin @ref com.kempt.app.data.BlockEvent type constants.
     * @param pending The @c goAsync() handle to finish when done, or @c null for a fire-and-forget
     * write (see @ref onDisableRequested for why one caller passes @c null).
     */
    private fun record(context: Context, type: String, pending: PendingResult?) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val event = BlockEvent(type = type)
                val id = deps.blockEventDao().insert(event)
                if (deps.accountability().report(event.copy(id = id))) {
                    deps.blockEventDao().markSynced(listOf(id))
                }
            } finally {
                pending?.finish()
            }
        }
    }
}
