package com.kempt.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kempt.app.data.BlockEventDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Periodically pings the backend so it knows the monitor is alive. If heartbeats stop
 * during an active lock, the backend notices the silence and notifies the partner.
 *
 * Each run also flushes any accountability events that were recorded while offline, so
 * the local log and the backend converge once connectivity returns.
 *
 * Dependencies are pulled from Hilt via an [EntryPoint] rather than constructor
 * injection, which keeps the default WorkManager initializer intact (no `hilt-work`).
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun accountability(): AccountabilityService
        fun blockEventDao(): BlockEventDao
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val accountability = deps.accountability()
        val dao = deps.blockEventDao()

        val heartbeatLanded = accountability.sendHeartbeat()

        // Flush anything that was recorded while the backend was unreachable.
        val delivered = dao.unsynced().filter { accountability.report(it) }.map { it.id }
        if (delivered.isNotEmpty()) dao.markSynced(delivered)

        return if (heartbeatLanded) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "kempt_heartbeat"

        /** Start the periodic heartbeat (idempotent — keeps an already-scheduled one). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
