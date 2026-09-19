package com.kempt.app.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kempt.app.util.Permissions
import com.kempt.app.data.BlockEvent
import com.kempt.app.data.BlockEventDao
import com.kempt.app.data.BlockRuleDao
import com.kempt.app.data.LockStateStore
import com.kempt.app.sync.AccountabilityService
import com.kempt.app.sync.HeartbeatWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Foreground service that watches the current foreground app and enforces active locks.
 *
 * The detection pipeline, once per [POLL_INTERVAL_MS]:
 *  1. If the lock is disarmed → tear down and stop.
 *  2. If usage access was revoked mid-lock → that's a tamper signal; record + report it.
 *  3. Find the foreground app from `UsageStatsManager.queryEvents()`.
 *  4. If it's a blocked app → draw the lock overlay, record the break, report it.
 *  5. Emit a heartbeat so the backend knows the monitor is alive.
 */
@AndroidEntryPoint
class AppMonitorService : Service() {

    @Inject lateinit var blockRuleDao: BlockRuleDao
    @Inject lateinit var blockEventDao: BlockEventDao
    @Inject lateinit var lockState: LockStateStore
    @Inject lateinit var accountability: AccountabilityService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var overlay: LockOverlay
    private var monitorJob: Job? = null

    /** The blocked package currently held behind the overlay, to avoid double-logging. */
    private var enforcingPackage: String? = null
    private var usageAccessLostReported = false

    override fun onCreate() {
        super.onCreate()
        overlay = LockOverlay(this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob == null) startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        monitorJob = scope.launch {
            while (isActive) {
                tick()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        if (!lockState.isArmedNow()) {
            teardownAndStop()
            return
        }

        if (!hasUsageAccess()) {
            if (!usageAccessLostReported) {
                usageAccessLostReported = true
                recordAndReport(BlockEvent.USAGE_ACCESS_LOST, null)
            }
            return
        }
        usageAccessLostReported = false

        val foreground = currentForegroundPackage()
        val blocked = blockRuleDao.enabledPackages().toSet()

        if (foreground != null && foreground != packageName && foreground in blocked) {
            if (enforcingPackage != foreground) {
                enforcingPackage = foreground
                recordAndReport(BlockEvent.BLOCK_ENFORCED, foreground)
            }
            val label = labelFor(foreground)
            withContext(Dispatchers.Main) {
                if (!overlay.isShowing) {
                    overlay.show(label) { code -> onPasscodeSubmit(code, foreground) }
                }
            }
        } else {
            enforcingPackage = null
            withContext(Dispatchers.Main) { if (overlay.isShowing) overlay.dismiss() }
        }

        accountability.sendHeartbeat()
    }

    /** Verify the partner passcode; success ends the lock, failure is reported as a break. */
    private fun onPasscodeSubmit(code: String, blockedPackage: String) {
        scope.launch {
            if (lockState.verifyPasscode(code)) {
                recordAndReport(BlockEvent.UNLOCK_SUCCESS, blockedPackage)
                lockState.disarm()
                HeartbeatWorker.cancel(this@AppMonitorService)
                teardownAndStop()
            } else {
                recordAndReport(BlockEvent.UNLOCK_FAILED, blockedPackage)
                withContext(Dispatchers.Main) { overlay.showError() }
            }
        }
    }

    private suspend fun teardownAndStop() {
        enforcingPackage = null
        withContext(Dispatchers.Main) { if (overlay.isShowing) overlay.dismiss() }
        stopSelf()
    }

    /** Records an event locally, then reports it; marks it synced only if the report lands. */
    private suspend fun recordAndReport(type: String, packageName: String?) {
        val event = BlockEvent(type = type, packageName = packageName)
        val id = blockEventDao.insert(event)
        if (accountability.report(event.copy(id = id))) {
            blockEventDao.markSynced(listOf(id))
        }
    }

    /** Most-recent app moved to the foreground within the lookback window, if any. */
    private fun currentForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - LOOKBACK_MS, end)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // ACTIVITY_RESUMED (API 29+) has the same value as the older, deprecated
            // MOVE_TO_FOREGROUND, so this one check covers every supported API level.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = event.packageName
            }
        }
        return latest
    }

    private fun hasUsageAccess(): Boolean = Permissions.hasUsageAccess(this)

    private fun labelFor(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, ensureChannel())
            .setContentTitle("Kempt is active")
            .setContentText("Watching for distracting apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kempt status", NotificationManager.IMPORTANCE_LOW)
        )
        return CHANNEL_ID
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        overlay.dismiss()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "kempt_status"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 1_000L
        private const val LOOKBACK_MS = 10_000L

        /** Start the monitor as a foreground service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppMonitorService::class.java))
        }
    }
}
