/**
 * @file
 * @brief WorkManager worker that periodically pings the backend and flushes unsynced events.
 */
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
 * @brief Periodic worker that pings the backend so it knows the monitor is alive.
 *
 * @details If heartbeats stop during an active lock, the backend notices the silence and
 * notifies the partner. Each run also flushes any accountability events recorded while
 * offline, so the local log and the backend converge once connectivity returns.
 *
 * @note Dependencies are pulled from Hilt via a @ref Deps @c @EntryPoint rather than
 * constructor injection, which keeps the default WorkManager initializer intact (no @c hilt-work).
 *
 * @param context The worker context (supplied by WorkManager).
 * @param params The worker parameters (supplied by WorkManager).
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /** @brief Hilt entry point exposing the dependencies this worker needs. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        /** @brief @return The accountability channel. */
        fun accountability(): AccountabilityService

        /** @brief @return The event DAO. */
        fun blockEventDao(): BlockEventDao
    }

    /**
     * @brief Sends a heartbeat and flushes any unsynced events.
     * @return @c Result.success() if the heartbeat landed, otherwise @c Result.retry() so
     * WorkManager runs the worker again later.
     */
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

    /** @brief Scheduling helpers and the unique work name. */
    companion object {
        /** @brief Unique work name so only one periodic heartbeat is ever scheduled. */
        private const val UNIQUE_NAME = "kempt_heartbeat"

        /**
         * @brief Schedules the periodic heartbeat (every 15 minutes, network required).
         * @details Idempotent: @c ExistingPeriodicWorkPolicy.KEEP leaves an already-scheduled
         * heartbeat untouched instead of restarting it.
         * @param context Any context.
         */
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

        /**
         * @brief Cancels the periodic heartbeat.
         * @param context Any context.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
