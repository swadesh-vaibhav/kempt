/**
 * @file
 * @brief The "Partner" tab: manage the partner passcode and review the accountability event log.
 */
package com.kempt.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kempt.app.ui.HomeUiState
import com.kempt.app.ui.components.EventLogCard
import com.kempt.app.ui.components.PasscodeForm

/**
 * @brief The third bottom-tab: the partner passcode form plus the recent-activity log.
 *
 * @details Both pieces are reused components — @ref com.kempt.app.ui.components.PasscodeForm and
 * @ref com.kempt.app.ui.components.EventLogCard. The column scrolls since the event log can be long.
 *
 * @param state The current UI state (passcode presence + recent events).
 * @param onSetPasscode Called with a new partner passcode to store it.
 * @param modifier Layout modifier.
 */
@Composable
fun PartnerTab(
    state: HomeUiState,
    onSetPasscode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PasscodeForm(hasPasscode = state.hasPasscode, onSetPasscode = onSetPasscode)
        EventLogCard(events = state.recentEvents)
    }
}
