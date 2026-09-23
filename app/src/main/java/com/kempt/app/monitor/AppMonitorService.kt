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

    /**
     * The blocked app we believe is on top right now, or null. This is *latched*: it's set
     * when a blocked app resumes and only cleared when that same app pauses — see
     * [updateLockTarget]. It is deliberately not recomputed from scratch each tick, so it
     * survives the bogus "android"/launcher foreground reports and quiet idle stretches
     * that broke the old single-poll detection.
     */
    private var lockTarget: String? = null

    /** Timestamp of the newest usage event we've already folded into [lockTarget]. */
    private var lastEventTime = 0L

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

        val blocked = blockRuleDao.enabledPackages().toSet()
        updateLockTarget(blocked)
        val target = lockTarget

        if (target != null) {
            if (enforcingPackage != target) {
                enforcingPackage = target
                recordAndReport(BlockEvent.BLOCK_ENFORCED, target)
            }
            val label = labelFor(target)
            withContext(Dispatchers.Main) {
                if (!overlay.isShowing) {
                    overlay.show(label) { code -> onPasscodeSubmit(code, target) }
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
        lockTarget = null
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

    /**
     * Fold every usage event we haven't seen yet into [lockTarget].
     *
     * The rule is a two-signal latch, not a snapshot:
     *  - A blocked app's own ACTIVITY_RESUMED arms the lock on that package.
     *  - That same package's ACTIVITY_PAUSED/STOPPED releases it.
     *  - Everything else is ignored — importantly, a RESUMED for a *non-blocked* app (the
     *    launcher, "android", SystemUI) never releases the lock. Some Android builds report
     *    those as the foreground while a blocked app is still genuinely on top; the old
     *    "latest RESUMED wins" logic believed them and tore the lock down. Android always
     *    pauses the outgoing app when you truly leave, so the blocked app's own pause is the
     *    only trustworthy release signal.
     *
     * We consume events incrementally from [lastEventTime] forward (rather than re-scanning a
     * fixed 10s window), so [lockTarget] persists across quiet stretches: if you sit on the
     * lock for minutes, no new event arrives, nothing clears the latch, and the overlay stays.
     */
    private fun updateLockTarget(blocked: Set<String>) {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // Cold start (lastEventTime == 0): look back a little to pick up an app opened just
        // before the monitor's first tick. Afterwards, resume from the last event we folded in.
        val begin = if (lastEventTime == 0L) now - LOOKBACK_MS else lastEventTime
        val events = usm.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // ACTIVITY_RESUMED (API 29+) shares its value with the deprecated
                // MOVE_TO_FOREGROUND, so this covers every supported API level.
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    if (event.packageName != packageName && event.packageName in blocked) {
                        lockTarget = event.packageName
                    }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (event.packageName == lockTarget) {
                        lockTarget = null
                    }
            }
            if (event.timeStamp > lastEventTime) lastEventTime = event.timeStamp
        }
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

        /** Stop the monitor (e.g. after an in-app disarm). Safe to call if it isn't running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AppMonitorService::class.java))
        }
    }
}
