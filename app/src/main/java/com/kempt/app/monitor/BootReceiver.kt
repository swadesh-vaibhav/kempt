package com.kempt.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the monitor and re-arms active blocks after the device reboots.
 * ("Reboot to escape" is a known bypass, so this closes it.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // TODO: only start if a block is currently active.
            AppMonitorService.start(context)
        }
    }
}
