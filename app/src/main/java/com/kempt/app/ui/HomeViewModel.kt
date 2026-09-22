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

data class HomeUiState(
    val isArmed: Boolean = false,
    val hasPasscode: Boolean = false,
    val rules: List<BlockRule> = emptyList(),
    val recentEvents: List<BlockEvent> = emptyList()
) {
    /** One tap can only arm once there's a passcode to unlock with and something to block. */
    val canLockDown: Boolean get() = !isArmed && hasPasscode && rules.any { it.enabled }
}

/** A launchable app the user can choose to block, shown in the picker with its icon. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val app: Application,
    private val lockState: LockStateStore,
    private val blockRuleDao: BlockRuleDao,
    private val blockEventDao: BlockEventDao,
    private val accountability: AccountabilityService
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        lockState.isArmed,
        lockState.hasPasscode,
        blockRuleDao.observeAll(),
        blockEventDao.observeRecent()
    ) { armed, hasPasscode, rules, events ->
        HomeUiState(armed, hasPasscode, rules, events)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // The list of installed apps for the picker. Loaded once, off the main thread, because
    // querying the PackageManager and decoding every app icon is too slow for the UI thread.
    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            _installedApps.value = loadInstalledApps()
        }
    }

    fun setPasscode(raw: String) {
        if (raw.isBlank()) return
        viewModelScope.launch { lockState.setPasscode(raw.trim()) }
    }

    /** Check/uncheck an app in the picker: [blocked] true adds a rule for it, false removes it. */
    fun setAppBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            if (blocked) {
                blockRuleDao.upsert(BlockRule(packageName = packageName, enabled = true))
            } else {
                blockRuleDao.delete(packageName)
            }
        }
    }

    /** One-tap lockdown: arm, start the monitor, schedule the heartbeat, log the event. */
    fun lockDown() {
        viewModelScope.launch {
            lockState.arm()
            AppMonitorService.start(app)
            HeartbeatWorker.schedule(app)
            recordAndReport(BlockEvent.LOCK_ARMED)
        }
    }

    /**
     * In-app disarm: verify the partner passcode and, on success, tear the lock down and stop
     * the monitor. [onResult] is called back (on the main thread) with whether it succeeded so
     * the screen can show an error on a wrong code.
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

    /** Records an event locally, then reports it; marks it synced only if the report lands. */
    private suspend fun recordAndReport(type: String, packageName: String? = null) {
        val event = BlockEvent(type = type, packageName = packageName)
        val id = blockEventDao.insert(event)
        if (accountability.report(event.copy(id = id))) {
            blockEventDao.markSynced(listOf(id))
        }
    }

    /** Every launchable app except Kempt itself, sorted by name, each with its icon decoded. */
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
