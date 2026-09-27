/**
 * @file
 * @brief Onboarding step 2: choose which installed apps to block.
 */
package com.kempt.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kempt.app.ui.InstalledApp
import com.kempt.app.ui.components.AppPickerList

/**
 * @brief Step 2 of onboarding: pick at least one app to block.
 *
 * @details Reuses @ref com.kempt.app.ui.components.AppPickerList (the same checkbox list used elsewhere)
 * under the wizard chrome. "Continue" enables only once at least one app is selected, mirroring the
 * @ref com.kempt.app.ui.HomeUiState.canLockDown rule that a lock needs at least one blocked app.
 *
 * @param installedApps The apps to list (loaded off the main thread by the ViewModel).
 * @param blockedPackages The package names currently selected.
 * @param onToggleApp Called with a package and its new checked state.
 * @param onContinue Called to advance to the passcode step.
 */
@Composable
fun OnboardingSelectAppsStep(
    installedApps: List<InstalledApp>,
    blockedPackages: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    onContinue: () -> Unit
) {
    OnboardingScaffold(
        title = "Choose apps to block",
        step = 2,
        totalSteps = 3,
        canAdvance = blockedPackages.isNotEmpty(),
        advanceLabel = "Continue",
        onAdvance = onContinue
    ) {
        Text(
            "Pick the apps you want locked during a lockdown. You can change this list later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // weight(1f) gives the lazy list a bounded height (the leftover after the intro text), which a
        // LazyColumn requires — it must never sit inside an unbounded/scrolling parent.
        AppPickerList(
            installedApps = installedApps,
            blockedPackages = blockedPackages,
            onToggleApp = onToggleApp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
}
