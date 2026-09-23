/**
 * @file
 * @brief Broadcast receiver that re-arms an active lock after the device reboots.
 */
package com.kempt.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kempt.app.data.BlockEvent
import com.kempt.app.data.BlockEventDao
import com.kempt.app.data.LockStateStore
import com.kempt.app.sync.AccountabilityService
import com.kempt.app.sync.HeartbeatWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * @brief Restarts the monitor and re-arms an active block after the device reboots.
 *
 * @details "Reboot to escape" is a known bypass, so this closes it. A block that was *not*
 * active at reboot is left alone — the service isn't started for nothing.
 *
 * @note Dependencies come from Hilt via a @ref Deps @c @EntryPoint rather than
 * @c @AndroidEntryPoint field injection, which keeps @c onReceive free of the
 * abstract-super boilerplate.
 */
class BootReceiver : BroadcastReceiver() {

    /** @brief Hilt entry point exposing the dependencies @c onReceive needs. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        /** @brief @return The lock-state store. */
        fun lockState(): LockStateStore

        /** @brief @return The event DAO. */
        fun blockEventDao(): BlockEventDao

        /** @brief @return The accountability channel. */
        fun accountability(): AccountabilityService
    }

    /**
     * @brief Handles @c BOOT_COMPLETED: if a lock was active, restarts the monitor, reschedules
     * the heartbeat, and records a re-arm event.
     *
     * @details @c goAsync() extends the receiver's short lifetime so the coroutine can finish
     * its database and network work; @c pending.finish() must be called when done (here in a
     * @c finally block) or the system may kill the process.
     *
     * @param context The receiver context.
     * @param intent The broadcast; ignored unless its action is @c ACTION_BOOT_COMPLETED.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val deps = EntryPointAccessors.fromApplication(appContext, Deps::class.java)
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (deps.lockState().isArmedNow()) {
                    AppMonitorService.start(appContext)
                    HeartbeatWorker.schedule(appContext)
                    val event = BlockEvent(type = BlockEvent.BOOT_REARM)
                    val id = deps.blockEventDao().insert(event)
                    if (deps.accountability().report(event.copy(id = id))) {
                        deps.blockEventDao().markSynced(listOf(id))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
