package com.kempt.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodically pings the backend so it knows the monitor is alive. If heartbeats stop
 * during an active lock, the backend notifies the accountability partner.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // TODO: POST a heartbeat to the backend.
        return Result.success()
    }
}
