/**
 * @file
 * @brief ViewModel and UI-state models backing the Kempt home screen.
 */
package com.kempt.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kempt.app.data.BlockEvent
import com.kempt.app.data.BlockEventDao
import com.kempt.app.data.BlockRule
import com.kempt.app.data.BlockRuleDao
import com.kempt.app.data.LockStateStore
import com.kempt.app.monitor.AppMonitorService
import com.kempt.app.sync.AccountabilityService
import com.kempt.app.sync.HeartbeatWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @brief Immutable snapshot of everything the home screen renders.
 * @property isArmed Whether a lock is currently active.
 * @property hasPasscode Whether a partner passcode has been set.
 * @property rules The user's block rules.
 * @property recentEvents The recent accountability events, newest first.
 */
data class HomeUiState(
    val isArmed: Boolean = false,
    val hasPasscode: Boolean = false,
    val rules: List<BlockRule> = emptyList(),
    val recentEvents: List<BlockEvent> = emptyList()
) {
    /** @brief Whether one-tap lockdown is allowed: not already armed, a passcode set, and at least one enabled rule. */
    val canLockDown: Boolean get() = !isArmed && hasPasscode && rules.any { it.enabled }
}

/**
 * @brief A launchable app the user can choose to block, shown in the picker with its icon.
 * @property packageName The app's package id.
 * @property label The user-visible app name.
 * @property icon The app icon, decoded to a Compose @c ImageBitmap.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

/**
 * @brief Backs the home screen: exposes UI state and handles the passcode, blocklist, lock, and disarm actions.
 *
 * @details @c @HiltViewModel lets Hilt supply the constructor dependencies. State is exposed
 * as @c StateFlow so Jetpack Compose can observe it and recompose on change.
 *
 * @param app The application, used for package queries and to start/stop the monitor.
 * @param lockState The lock-state and passcode store.
 * @param blockRuleDao DAO for block rules.
 * @param blockEventDao DAO for accountability events.
 * @param accountability Outbound channel for event reports.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val app: Application,
    private val lockState: LockStateStore,
    private val blockRuleDao: BlockRuleDao,
    private val blockEventDao: BlockEventDao,
    private val accountability: AccountabilityService
) : ViewModel() {

    /**
     * @brief The observable home-screen state, combined from the lock flags, rules, and events.
     * @details @c combine merges the four source flows into one; @c stateIn caches the latest
     * value and keeps the upstream alive for 5s after the last observer leaves, which avoids
     * restarting the flows on a configuration change such as rotation.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        lockState.isArmed,
        lockState.hasPasscode,
        blockRuleDao.observeAll(),
        blockEventDao.observeRecent()
    ) { armed, hasPasscode, rules, events ->
        HomeUiState(armed, hasPasscode, rules, events)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** @brief Mutable backing state for the installed-app list (the underscore-prefixed private half of the pair). */
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())

    /**
     * @brief Read-only view of the installed apps for the picker.
     * @details Loaded once, off the main thread, because querying the PackageManager and
     * decoding every app icon is too slow for the UI thread.
     */
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    /** @brief Kicks off the one-time installed-app load on a background dispatcher when the ViewModel is created. */
    init {
        viewModelScope.launch(Dispatchers.Default) {
            _installedApps.value = loadInstalledApps()
        }
    }

    /**
     * @brief Sets the partner passcode, ignoring blank input.
     * @param raw The plaintext passcode; trimmed before it is hashed and stored.
     */
    fun setPasscode(raw: String) {
        if (raw.isBlank()) return
        viewModelScope.launch { lockState.setPasscode(raw.trim()) }
    }

    /**
     * @brief Checks or unchecks an app in the picker.
     * @param packageName The app to toggle.
     * @param blocked @c true adds a rule for it, @c false removes the rule.
     */
    fun setAppBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            if (blocked) {
                blockRuleDao.upsert(BlockRule(packageName = packageName, enabled = true))
            } else {
                blockRuleDao.delete(packageName)
            }
        }
    }

    /** @brief One-tap lockdown: arm the lock, start the monitor, schedule the heartbeat, and log the event. */
    fun lockDown() {
        viewModelScope.launch {
            lockState.arm()
            AppMonitorService.start(app)
            HeartbeatWorker.schedule(app)
            recordAndReport(BlockEvent.LOCK_ARMED)
        }
    }

    /**
     * @brief In-app disarm: verify the partner passcode and, on success, tear the lock down and stop the monitor.
     * @param rawCode The passcode entered by the user (trimmed before checking).
     * @param onResult Called back on the main thread with whether the code was correct, so the
     * screen can show an error on a wrong code.
     */
    fun disarm(rawCode: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = lockState.verifyPasscode(rawCode.trim())
            if (success) {
                recordAndReport(BlockEvent.UNLOCK_SUCCESS)
                lockState.disarm()
                AppMonitorService.stop(app)
                HeartbeatWorker.cancel(app)
            } else {
                recordAndReport(BlockEvent.UNLOCK_FAILED)
            }
            onResult(success)
        }
    }

    /**
     * @brief Records an event locally, reports it, and marks it synced only if the report lands.
     * @param type One of the @ref com.kempt.app.data.BlockEvent type constants.
     * @param packageName The app the event concerns, or @c null.
     */
    private suspend fun recordAndReport(type: String, packageName: String? = null) {
        val event = BlockEvent(type = type, packageName = packageName)
        val id = blockEventDao.insert(event)
        if (accountability.report(event.copy(id = id))) {
            blockEventDao.markSynced(listOf(id))
        }
    }

    /**
     * @brief Builds the picker's app list: every launchable app except Kempt, sorted by name, each icon decoded.
     * @return The installed apps, ready to display.
     */
    private fun loadInstalledApps(): List<InstalledApp> {
        val pm = app.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != app.packageName }
            .mapNotNull { resolveInfo ->
                runCatching {
                    InstalledApp(
                        packageName = resolveInfo.activityInfo.packageName,
                        label = resolveInfo.loadLabel(pm).toString(),
                        icon = resolveInfo.loadIcon(pm).toBitmap().asImageBitmap()
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }
}
