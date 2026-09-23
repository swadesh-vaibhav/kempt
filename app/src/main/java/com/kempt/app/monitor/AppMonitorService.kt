/**
 * @file
 * @brief Foreground service that watches the foreground app and enforces active locks.
 */
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
import android.provider.Settings
import android.util.Log
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
 * @brief Foreground service that watches the current foreground app and enforces active locks.
 *
 * @details Runs a polling loop once per #POLL_INTERVAL_MS:
 *  1. If the lock is disarmed → tear down and stop.
 *  2. If usage access was revoked mid-lock → treat as a tamper signal; record and report it.
 *  3. Find the foreground app from @c UsageStatsManager.queryEvents().
 *  4. If it's a blocked app → draw the lock overlay, record the break, report it.
 *  5. Emit a heartbeat so the backend knows the monitor is alive.
 *
 * @note @c @AndroidEntryPoint lets Hilt inject the @c lateinit fields below into this
 * framework-owned component. @c lateinit is a Kotlin promise that a non-null @c var will be
 * assigned before first use (here, by Hilt), which avoids making the fields nullable.
 */
@AndroidEntryPoint
class AppMonitorService : Service() {

    /** @brief DAO for reading which packages are blocked. Injected by Hilt. */
    @Inject lateinit var blockRuleDao: BlockRuleDao

    /** @brief DAO for recording accountability events. Injected by Hilt. */
    @Inject lateinit var blockEventDao: BlockEventDao

    /** @brief Store of the armed flag and passcode, read each tick. Injected by Hilt. */
    @Inject lateinit var lockState: LockStateStore

    /** @brief Outbound channel to the backend for reports and heartbeats. Injected by Hilt. */
    @Inject lateinit var accountability: AccountabilityService

    /**
     * @brief Coroutine scope for the polling loop.
     * @note A @c SupervisorJob means one child coroutine failing doesn't cancel its siblings.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** @brief The lock UI drawn over blocked apps; created in #onCreate. */
    private lateinit var overlay: LockOverlay

    /** @brief Handle to the running polling loop, or @c null before it starts; used to cancel on destroy. */
    private var monitorJob: Job? = null

    /** @brief The blocked package currently held behind the overlay, tracked to avoid double-logging one break. */
    private var enforcingPackage: String? = null

    /** @brief Guards against re-reporting the same usage-access-revoked tamper event every tick. */
    private var usageAccessLostReported = false

    /**
     * @brief The blocked app believed to be on top right now, or @c null.
     *
     * @details This value is *latched*: set when a blocked app resumes and cleared only when
     * that same app pauses — see #updateLockTarget. It is deliberately not recomputed from
     * scratch each tick, so it survives the bogus "android"/launcher foreground reports and
     * quiet idle stretches that broke the old single-poll detection.
     */
    private var lockTarget: String? = null

    /** @brief Timestamp of the newest usage event already folded into #lockTarget, so each tick only reads new ones. */
    private var lastEventTime = 0L

    /**
     * @brief Packages force-blocked whenever a lock is armed, on top of the user's blocklist.
     *
     * @details The system Settings app lives here because Settings is the one place a user
     * could go to weaken the lock mid-session: revoke Kempt's usage-access or overlay
     * permission, force-stop the monitor service, or uninstall the app outright. Slamming the
     * overlay over Settings while armed keeps the lock tamper-resistant.
     *
     * @note @c by lazy { ... } is a Kotlin delegated property: the block runs the first time
     * this value is read and the result is cached, so the package lookup happens at most once
     * per service instance instead of on every one-second tick.
     */
    private val forceBlockedPackages: Set<String> by lazy {
        // buildSet { } gives us a temporary MutableSet to add into, and returns an immutable
        // Set. A Set de-duplicates, so it's fine if both entries below resolve to the same name.
        buildSet {
            // AOSP's canonical Settings package — present on Pixel, Samsung, and most OEMs.
            add(SETTINGS_PACKAGE)
            // Also ask the system which app actually handles the top-level Settings screen, to
            // cover OEM builds that ship Settings under a different package name. The `?.` calls
            // short-circuit to null if nothing handles the intent, in which case we just keep
            // the hard-coded fallback above.
            val handler = packageManager
                .resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
                ?.activityInfo
                ?.packageName
            if (handler != null) add(handler)
        }
    }

    /** @brief Creates the overlay and promotes this service to the foreground. */
    override fun onCreate() {
        super.onCreate()
        overlay = LockOverlay(this)
        startAsForeground()
    }

    /**
     * @brief Starts the polling loop on the first start command.
     * @param intent The start intent (unused).
     * @param flags Start flags (unused).
     * @param startId A unique id for this start request (unused).
     * @return @c START_STICKY so Android recreates the service if it's killed while running.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob == null) startMonitoring()
        return START_STICKY
    }

    /** @brief Launches the coroutine that calls #tick every #POLL_INTERVAL_MS until cancelled. */
    private fun startMonitoring() {
        monitorJob = scope.launch {
            while (isActive) {
                tick()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * @brief One iteration of the detection pipeline (see the class docs for the full sequence).
     * @details Stops the service if disarmed, reports a tamper event if usage access was lost,
     * updates the lock target, shows or dismisses the overlay accordingly, and sends a heartbeat.
     */
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

        // `+` on two Sets returns a new Set that is their union. We reach this line only after
        // the isArmedNow() guard above returned, so the Settings package is added to the block
        // list exactly when — and only when — a lock is armed.
        val blocked = blockRuleDao.enabledPackages().toSet() + forceBlockedPackages
        updateLockTarget(blocked)
        val target = lockTarget

        if (target != null) {
            if (enforcingPackage != target) {
                enforcingPackage = target
                recordAndReport(BlockEvent.BLOCK_ENFORCED, target)
            }
            if (target in forceBlockedPackages) {
                // The OS suppresses our overlay window over Settings, so bring up a real
                // full-screen Activity instead — it can't be hidden the same way. Safe to call
                // each tick: once the lock Activity is on top, Settings leaves the foreground and
                // the latch (lockTarget) clears, so this stops firing on the very next tick.
                LockActivity.start(this)
            } else {
                val label = labelFor(target)
                withContext(Dispatchers.Main) {
                    if (!overlay.isShowing) {
                        overlay.show(label) { code -> onPasscodeSubmit(code, target) }
                    }
                }
            }
        } else {
            enforcingPackage = null
            withContext(Dispatchers.Main) { if (overlay.isShowing) overlay.dismiss() }
        }

        accountability.sendHeartbeat()
    }

    /**
     * @brief Handles a passcode entered on the overlay: success ends the lock, failure is reported as a break.
     * @param code The passcode the user typed.
     * @param blockedPackage The app that triggered the lock, recorded on the resulting event.
     */
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

    /** @brief Clears lock state, dismisses the overlay, and stops the service. */
    private suspend fun teardownAndStop() {
        enforcingPackage = null
        lockTarget = null
        withContext(Dispatchers.Main) { if (overlay.isShowing) overlay.dismiss() }
        stopSelf()
    }

    /**
     * @brief Records an event locally, reports it to the backend, and marks it synced only if the report lands.
     * @param type One of the @ref com.kempt.app.data.BlockEvent type constants.
     * @param packageName The app the event concerns, or @c null.
     */
    private suspend fun recordAndReport(type: String, packageName: String?) {
        val event = BlockEvent(type = type, packageName = packageName)
        val id = blockEventDao.insert(event)
        if (accountability.report(event.copy(id = id))) {
            blockEventDao.markSynced(listOf(id))
        }
    }

    /**
     * @brief Folds every not-yet-seen usage event into #lockTarget using a two-signal latch.
     *
     * @details The rule is a latch, not a snapshot:
     *  - A blocked app's own @c ACTIVITY_RESUMED arms the lock on that package.
     *  - That same package's @c ACTIVITY_PAUSED / @c ACTIVITY_STOPPED releases it.
     *  - Everything else is ignored — importantly, a RESUMED for a *non-blocked* app (the
     *    launcher, "android", SystemUI) never releases the lock. Some Android builds report
     *    those as the foreground while a blocked app is still genuinely on top; the old
     *    "latest RESUMED wins" logic believed them and tore the lock down. Android always
     *    pauses the outgoing app when you truly leave, so the blocked app's own pause is the
     *    only trustworthy release signal.
     *
     * Events are consumed incrementally from #lastEventTime forward (rather than re-scanning a
     * fixed 10s window), so #lockTarget persists across quiet stretches: sit on the lock for
     * minutes, no new event arrives, nothing clears the latch, and the overlay stays.
     *
     * @param blocked The set of currently blocked package names.
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
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Debug aid: log every app that comes to the foreground so we can read its
                    // real package name in logcat (e.g. what "Settings" actually resolves to on
                    // this device). Filter with: adb logcat -s AppMonitor
                    Log.d(TAG, "foreground -> ${event.packageName}")
                    if (event.packageName != packageName && event.packageName in blocked) {
                        lockTarget = event.packageName
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (event.packageName == lockTarget) {
                        lockTarget = null
                    }
            }
            if (event.timeStamp > lastEventTime) lastEventTime = event.timeStamp
        }
    }

    /** @brief @return @c true if the app still holds usage access. */
    private fun hasUsageAccess(): Boolean = Permissions.hasUsageAccess(this)

    /**
     * @brief Resolves a package's user-visible app name, falling back to the package id.
     * @param pkg The package to look up.
     * @return The app label, or @p pkg if it can't be resolved.
     */
    private fun labelFor(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    /**
     * @brief Posts the ongoing notification and promotes the service to the foreground.
     * @details On Android 14+ a foreground-service type (@c FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
     * must be declared at start time; older versions use the two-argument call.
     */
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

    /**
     * @brief Creates (idempotently) the low-importance notification channel for the status notification.
     * @return The channel id to attach the notification to.
     */
    private fun ensureChannel(): String {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kempt status", NotificationManager.IMPORTANCE_LOW)
        )
        return CHANNEL_ID
    }

    /** @brief Cancels the polling loop and its scope, and removes the overlay. */
    override fun onDestroy() {
        monitorJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        overlay.dismiss()
        super.onDestroy()
    }

    /**
     * @brief This service isn't bindable.
     * @param intent Unused.
     * @return Always @c null.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /** @brief Service-wide constants and the start/stop helpers other code uses. */
    companion object {
        /** @brief Logcat tag for this service's diagnostics. Filter with: adb logcat -s AppMonitor */
        private const val TAG = "AppMonitor"

        /** @brief AOSP's canonical Settings package, force-blocked while armed. */
        private const val SETTINGS_PACKAGE = "com.android.settings"

        /** @brief Notification channel id for the ongoing status notification. */
        private const val CHANNEL_ID = "kempt_status"

        /** @brief Id of the foreground-service status notification. */
        private const val NOTIFICATION_ID = 1001

        /** @brief How often the detection loop runs, in milliseconds. */
        private const val POLL_INTERVAL_MS = 1_000L

        /** @brief On cold start, how far back to scan usage events to catch an app opened just before the first tick. */
        private const val LOOKBACK_MS = 10_000L

        /**
         * @brief Starts the monitor as a foreground service.
         * @param context Any context.
         */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppMonitorService::class.java))
        }

        /**
         * @brief Stops the monitor (e.g. after an in-app disarm). Safe to call when it isn't running.
         * @param context Any context.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, AppMonitorService::class.java))
        }
    }
}
