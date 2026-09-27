/**
 * @file
 * @brief The permission-management screen reached from the hamburger drawer.
 */
package com.kempt.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kempt.app.ui.components.PermissionRow
import com.kempt.app.ui.components.SectionCard
import com.kempt.app.ui.components.rememberPermissionStatus
import com.kempt.app.util.Permissions

/**
 * @brief Full permission-management screen: review and change all four special-access grants any time.
 *
 * @details Unlike the onboarding step (which reveals one grant at a time), this shows every permission
 * with its own control at once — the required two (usage access, overlay), plus the optional battery
 * exemption ("Open") and uninstall protection (Enable / Turn off). Grants happen in system Settings;
 * @ref com.kempt.app.ui.components.rememberPermissionStatus re-checks on resume, so the ✓ updates when
 * the user returns. Only reachable while not armed (the main shell hides the drawer during a lockdown).
 *
 * @param onBack Pops back to the main screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Bumped after an in-app change (device-admin turn-off) that doesn't trigger a resume, so the row
    // updates immediately.
    var refreshSignal by remember { mutableIntStateOf(0) }
    val status = rememberPermissionStatus(refreshSignal)

    // Device admin is granted/revoked via a system consent screen returning a result; launching for a
    // result means the on-resume re-check reflects the change automatically.
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* rememberPermissionStatus refreshes on ON_RESUME */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Required") {
                PermissionRow("Usage access", status.usageAccess) {
                    if (!status.usageAccess) {
                        OutlinedButton(onClick = {
                            context.startActivity(Permissions.usageAccessSettings())
                        }) { Text("Grant") }
                    }
                }
                PermissionRow("Draw over other apps", status.overlay) {
                    if (!status.overlay) {
                        OutlinedButton(onClick = {
                            context.startActivity(Permissions.overlaySettings(context))
                        }) { Text("Grant") }
                    }
                }
            }

            SectionCard("Optional (recommended)") {
                PermissionRow("Battery exemption", status.battery) {
                    if (!status.battery) {
                        OutlinedButton(onClick = {
                            context.startActivity(Permissions.batteryOptimizationSettings())
                        }) { Text("Open") }
                    }
                }
                PermissionRow("Uninstall protection", status.deviceAdmin) {
                    if (status.deviceAdmin) {
                        OutlinedButton(onClick = {
                            Permissions.removeDeviceAdmin(context)
                            refreshSignal++
                        }) {
                            Text("Turn off")
                        }
                    } else {
                        OutlinedButton(onClick = {
                            deviceAdminLauncher.launch(Permissions.addDeviceAdminIntent(context))
                        }) { Text("Enable") }
                    }
                }
                Text(
                    "Uninstall protection makes Kempt harder to remove and reports removal to your " +
                        "partner. You can turn it off here while no lock is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
