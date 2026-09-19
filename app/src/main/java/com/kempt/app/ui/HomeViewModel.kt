package com.kempt.app.ui

import android.app.Application
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun setPasscode(raw: String) {
        if (raw.isBlank()) return
        viewModelScope.launch { lockState.setPasscode(raw.trim()) }
    }

    fun addBlockedApp(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        viewModelScope.launch { blockRuleDao.upsert(BlockRule(packageName = pkg, enabled = true)) }
    }

    fun removeBlockedApp(packageName: String) {
        viewModelScope.launch { blockRuleDao.delete(packageName) }
    }

    /** One-tap lockdown: arm, start the monitor, schedule the heartbeat, log the event. */
    fun lockDown() {
        viewModelScope.launch {
            lockState.arm()
            AppMonitorService.start(app)
            HeartbeatWorker.schedule(app)
            val event = BlockEvent(type = BlockEvent.LOCK_ARMED)
            val id = blockEventDao.insert(event)
            if (accountability.report(event.copy(id = id))) {
                blockEventDao.markSynced(listOf(id))
            }
        }
    }
}
