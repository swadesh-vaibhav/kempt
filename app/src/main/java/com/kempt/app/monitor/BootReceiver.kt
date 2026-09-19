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
 * Restarts the monitor and re-arms active blocks after the device reboots.
 * ("Reboot to escape" is a known bypass, so this closes it.) A block that was *not*
 * active at reboot is left alone — we don't start the service for nothing.
 *
 * Dependencies come from Hilt via an [EntryPoint] rather than `@AndroidEntryPoint`
 * field injection, which keeps `onReceive` free of the abstract-super dance.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun lockState(): LockStateStore
        fun blockEventDao(): BlockEventDao
        fun accountability(): AccountabilityService
    }

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
