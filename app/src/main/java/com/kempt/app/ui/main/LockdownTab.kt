/**
 * @file
 * @brief The middle "Lockdown" tab: a single large button that arms the lock.
 */
package com.kempt.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kempt.app.ui.HomeUiState

/**
 * @brief The centre bottom-tab: one big "Lock down" button.
 *
 * @details The button is enabled only when a lock can actually work — the shared
 * @ref com.kempt.app.ui.HomeUiState.canLockDown rule (a passcode is set and at least one app is
 * blocked) plus the two required permissions being granted. When it's disabled, helper text explains
 * what's still missing.
 *
 * @param state The current UI state (drives @c canLockDown and the disabled-reason text).
 * @param hasUsageAccess Whether usage access is granted.
 * @param hasOverlay Whether the overlay permission is granted.
 * @param onLockDown Called to arm the lock.
 * @param modifier Layout modifier.
 */
@Composable
fun LockdownTab(
    state: HomeUiState,
    hasUsageAccess: Boolean,
    hasOverlay: Boolean,
    onLockDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = state.canLockDown && hasUsageAccess && hasOverlay

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Ready to focus?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "One tap locks your chosen apps. Only your partner's passcode ends it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onLockDown,
            enabled = enabled,
            contentPadding = PaddingValues(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null)
            Text(
                "  Lock down",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        if (!enabled) {
            Text(
                text = disabledReason(state, hasUsageAccess, hasOverlay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * @brief Builds the helper line explaining why lockdown is unavailable.
 * @param state The current UI state.
 * @param hasUsageAccess Whether usage access is granted.
 * @param hasOverlay Whether the overlay permission is granted.
 * @return A short sentence naming what's still needed.
 */
private fun disabledReason(state: HomeUiState, hasUsageAccess: Boolean, hasOverlay: Boolean): String {
    val missing = buildList {
        if (!state.hasPasscode) add("set a partner passcode")
        if (!state.rules.any { it.enabled }) add("add at least one app")
        if (!hasUsageAccess) add("grant usage access")
        if (!hasOverlay) add("grant draw-over-apps")
    }
    return "To lock down, " + missing.joinToString(", ") + "."
}
