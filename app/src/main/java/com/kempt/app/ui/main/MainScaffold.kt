/**
 * @file
 * @brief The main app shell: a navigation drawer + top bar + 3-tab bottom bar, or the locked screen.
 */
package com.kempt.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kempt.app.ui.HomeUiState
import com.kempt.app.ui.InstalledApp
import com.kempt.app.ui.components.rememberPermissionStatus
import com.kempt.app.ui.navigation.MainTab
import kotlinx.coroutines.launch

/**
 * @brief The main app screen once onboarding is done.
 *
 * @details Two shapes in one:
 * - While a lock is **armed** (@c state.isArmed), the whole shell collapses to @ref LockedScreen — no
 *   tabs, no drawer, no way to reach Settings — so the only path forward is the partner passcode.
 * - Otherwise it draws a @c ModalNavigationDrawer (opened by the top-left hamburger) wrapping a
 *   @c Scaffold with a top bar and a three-item bottom navigation bar; the selected tab's content is
 *   swapped in by a @c when.
 *
 * The selected tab is plain state (no per-tab back stack is needed), kept in @c rememberSaveable so it
 * survives rotation. Permission status is read here (auto-refreshing on resume) and handed to the
 * Lockdown tab to gate its button.
 *
 * @param state The current UI state.
 * @param installedApps All launchable apps (for the Apps tab / picker).
 * @param blockedPackages The currently blocked package names.
 * @param onToggleApp Called with a package and its new checked state.
 * @param onSetPasscode Called with a new partner passcode.
 * @param onLockDown Called to arm the lock.
 * @param onDisarm Called with the entered code and a success callback to attempt an unlock.
 * @param onOpenPermissions Navigates to the permission-management screen (from the drawer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    state: HomeUiState,
    installedApps: List<InstalledApp>,
    blockedPackages: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    onSetPasscode: (String) -> Unit,
    onLockDown: () -> Unit,
    onDisarm: (String, (Boolean) -> Unit) -> Unit,
    onOpenPermissions: () -> Unit
) {
    // Armed => single-button lock screen; nothing else is reachable while a lock is active.
    if (state.isArmed) {
        LockedScreen(onDisarm = onDisarm)
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(MainTab.LOCKDOWN) }
    val perms = rememberPermissionStatus()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Permissions") },
                    icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenPermissions()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                // More drawer items can be added here later.
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Kempt") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == MainTab.APPS,
                        onClick = { tab = MainTab.APPS },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("Apps") }
                    )
                    NavigationBarItem(
                        selected = tab == MainTab.LOCKDOWN,
                        onClick = { tab = MainTab.LOCKDOWN },
                        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        label = { Text("Lockdown") }
                    )
                    NavigationBarItem(
                        selected = tab == MainTab.PARTNER,
                        onClick = { tab = MainTab.PARTNER },
                        icon = { Icon(Icons.Filled.People, contentDescription = null) },
                        label = { Text("Partner") }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (tab) {
                    MainTab.APPS -> AppsTab(installedApps, blockedPackages, onToggleApp)
                    MainTab.LOCKDOWN -> LockdownTab(state, perms.usageAccess, perms.overlay, onLockDown)
                    MainTab.PARTNER -> PartnerTab(state, onSetPasscode)
                }
            }
        }
    }
}
