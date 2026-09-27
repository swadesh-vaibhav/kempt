/**
 * @file
 * @brief The "Apps" tab: shows the currently blocked apps and opens the picker to change them.
 */
package com.kempt.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kempt.app.ui.InstalledApp
import com.kempt.app.ui.components.AppPickerScreen

/**
 * @brief The first bottom-tab: lists the apps currently on the blocklist and lets the user edit it.
 *
 * @details When the user taps "Select apps to block" this swaps in the full-screen
 * @ref com.kempt.app.ui.components.AppPickerScreen (tracked by a local, rotation-surviving boolean).
 * The blocked apps are resolved to their labels/icons via @p installedApps; any package not found there
 * (e.g. uninstalled since) falls back to showing its package name.
 *
 * @param installedApps All launchable apps, used to resolve labels/icons for the blocked set.
 * @param blockedPackages The package names currently blocked.
 * @param onToggleApp Called with a package and its new checked state.
 * @param modifier Layout modifier.
 */
@Composable
fun AppsTab(
    installedApps: List<InstalledApp>,
    blockedPackages: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    if (showPicker) {
        AppPickerScreen(
            installedApps = installedApps,
            blockedPackages = blockedPackages,
            onToggleApp = onToggleApp,
            onClose = { showPicker = false },
            modifier = modifier
        )
        return
    }

    // The blocked apps, resolved to display models (label + icon), sorted by name.
    val blockedApps = remember(installedApps, blockedPackages) {
        installedApps.filter { it.packageName in blockedPackages }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Blocked apps", style = MaterialTheme.typography.titleLarge)
        Text(
            text = when (blockedPackages.size) {
                0 -> "No apps selected yet."
                1 -> "1 app will be locked during a lockdown."
                else -> "${blockedPackages.size} apps will be locked during a lockdown."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Select apps to block") }

        if (blockedApps.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(blockedApps, key = { it.packageName }) { app ->
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                    HorizontalDivider()
                }
            }
        } else if (blockedPackages.isNotEmpty()) {
            // Rules exist but their apps aren't in the launchable list (rare); show the raw packages.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(blockedPackages.toList(), key = { it }) { pkg ->
                    Text(
                        pkg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
