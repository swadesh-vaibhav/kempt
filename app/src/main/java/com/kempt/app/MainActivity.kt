/**
 * @file
 * @brief The single activity and Jetpack Compose UI for the Kempt home and app-picker screens.
 */
package com.kempt.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.kempt.app.data.BlockEvent
import com.kempt.app.ui.HomeUiState
import com.kempt.app.ui.HomeViewModel
import com.kempt.app.ui.InstalledApp
import com.kempt.app.ui.theme.KemptTheme
import com.kempt.app.util.Permissions
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @brief The app's only activity; hosts the entire Compose UI.
 * @details @c @AndroidEntryPoint enables Hilt injection, which is what lets @c hiltViewModel()
 * inside the composables obtain a @ref com.kempt.app.ui.HomeViewModel.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * @brief Enables edge-to-edge drawing and installs the Compose content tree.
     * @param savedInstanceState The standard saved-state bundle (unused here).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KemptTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeRoute(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * @brief Stateful entry composable that switches between the home screen and the app picker.
 *
 * @details Collects the ViewModel state, requests the notification permission on first
 * composition (Android 13+), and recomputes the special-access permission flags whenever the
 * user returns from a Settings screen.
 *
 * @param modifier Layout modifier from the hosting scaffold.
 * @param viewModel The screen's ViewModel, supplied by Hilt via @c hiltViewModel().
 */
@Composable
private fun HomeRoute(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val context = LocalContext.current

    // Recompute the special-access permission state whenever we come back from Settings.
    var permissionTick by remember { mutableIntStateOf(0) }
    val hasUsageAccess = remember(permissionTick) { Permissions.hasUsageAccess(context) }
    val hasOverlay = remember(permissionTick) { Permissions.canDrawOverlays(context) }
    val hasUninstallProtection = remember(permissionTick) { Permissions.isDeviceAdminActive(context) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionTick++ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Whether the full-screen app picker is open on top of the home screen.
    var showAppPicker by remember { mutableStateOf(false) }
    val blockedPackages = state.rules.map { it.packageName }.toSet()

    if (showAppPicker) {
        AppPickerScreen(
            installedApps = installedApps,
            blockedPackages = blockedPackages,
            onToggleApp = viewModel::setAppBlocked,
            onClose = { showAppPicker = false },
            modifier = modifier
        )
    } else {
        HomeScreen(
            state = state,
            selectedAppCount = blockedPackages.size,
            hasUsageAccess = hasUsageAccess,
            hasOverlay = hasOverlay,
            hasUninstallProtection = hasUninstallProtection,
            onRefreshPermissions = { permissionTick++ },
            onSetPasscode = viewModel::setPasscode,
            onOpenAppPicker = { showAppPicker = true },
            onLockDown = viewModel::lockDown,
            onDisarm = viewModel::disarm,
            modifier = modifier
        )
    }
}

/**
 * @brief The main home screen: title plus either the locked card or the setup cards and the
 * "Lock down" button, followed by the recent-activity log.
 *
 * @param state The current UI state.
 * @param selectedAppCount How many apps are currently on the blocklist.
 * @param hasUsageAccess Whether usage access is granted.
 * @param hasOverlay Whether the overlay permission is granted.
 * @param hasUninstallProtection Whether Kempt is an active device admin (uninstall protection on).
 * @param onRefreshPermissions Called to re-check permissions after returning from Settings.
 * @param onSetPasscode Called with a new partner passcode.
 * @param onOpenAppPicker Called to open the app picker.
 * @param onLockDown Called to arm the lock.
 * @param onDisarm Called with the entered code and a success callback to attempt an unlock.
 * @param modifier Layout modifier.
 */
@Composable
private fun HomeScreen(
    state: HomeUiState,
    selectedAppCount: Int,
    hasUsageAccess: Boolean,
    hasOverlay: Boolean,
    hasUninstallProtection: Boolean,
    onRefreshPermissions: () -> Unit,
    onSetPasscode: (String) -> Unit,
    onOpenAppPicker: () -> Unit,
    onLockDown: () -> Unit,
    onDisarm: (String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // The "activate device admin?" screen returns a result, so launch it for one and refresh the
    // permission flags when the user comes back — no manual "refresh" tap needed for this grant.
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onRefreshPermissions() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Kempt", style = MaterialTheme.typography.headlineMedium)
        Text(
            "One tap to lock down. Someone else lets you back in.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (state.isArmed) {
            LockedCard(onDisarm = onDisarm)
        } else {
            PermissionsCard(
                hasUsageAccess = hasUsageAccess,
                hasOverlay = hasOverlay,
                hasUninstallProtection = hasUninstallProtection,
                onOpenUsageAccess = {
                    context.startActivity(Permissions.usageAccessSettings())
                },
                onOpenOverlay = {
                    context.startActivity(Permissions.overlaySettings(context))
                },
                onOpenBattery = {
                    context.startActivity(Permissions.batteryOptimizationSettings())
                },
                onEnableUninstallProtection = {
                    deviceAdminLauncher.launch(Permissions.addDeviceAdminIntent(context))
                },
                onDisableUninstallProtection = {
                    Permissions.removeDeviceAdmin(context)
                    onRefreshPermissions()
                },
                onRefresh = onRefreshPermissions
            )
            PasscodeCard(hasPasscode = state.hasPasscode, onSetPasscode = onSetPasscode)
            BlocklistCard(
                selectedCount = selectedAppCount,
                onOpenAppPicker = onOpenAppPicker
            )
            Button(
                onClick = onLockDown,
                enabled = state.canLockDown && hasUsageAccess && hasOverlay,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Lock down") }
            if (!state.canLockDown) {
                Text(
                    "Set a partner passcode and add at least one app to enable lockdown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        EventLogCard(events = state.recentEvents)
    }
}

/**
 * @brief Card shown while armed: prompts for the partner passcode and reports wrong entries.
 * @param onDisarm Called with the entered code and a callback that receives whether it succeeded.
 */
@Composable
private fun LockedCard(onDisarm: (String, (Boolean) -> Unit) -> Unit) {
    var code by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    SectionCard("Locked") {
        Text(
            "Your distracting apps are locked. Enter the passcode held by your accountability " +
                "partner to disarm.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; showError = false },
            label = { Text("Partner passcode") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (showError) {
            Text(
                "Wrong passcode. Your partner has been notified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            onClick = { onDisarm(code) { success -> showError = !success; code = "" } },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enter code & disarm") }
    }
}

/**
 * @brief Card listing the required and optional special-access permissions with grant buttons.
 * @param hasUsageAccess Whether usage access is granted.
 * @param hasOverlay Whether the overlay permission is granted.
 * @param hasUninstallProtection Whether uninstall protection (device admin) is active.
 * @param onOpenUsageAccess Opens the usage-access settings screen.
 * @param onOpenOverlay Opens the overlay settings screen.
 * @param onOpenBattery Opens the battery-optimization settings screen.
 * @param onEnableUninstallProtection Launches the "activate device admin?" consent screen.
 * @param onDisableUninstallProtection Turns uninstall protection off (removes Kempt's device admin).
 * @param onRefresh Re-checks permissions after the user grants them.
 */
@Composable
private fun PermissionsCard(
    hasUsageAccess: Boolean,
    hasOverlay: Boolean,
    hasUninstallProtection: Boolean,
    onOpenUsageAccess: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenBattery: () -> Unit,
    onEnableUninstallProtection: () -> Unit,
    onDisableUninstallProtection: () -> Unit,
    onRefresh: () -> Unit
) {
    SectionCard("Permissions") {
        PermissionRow("Usage access", hasUsageAccess, onOpenUsageAccess)
        PermissionRow("Draw over other apps", hasOverlay, onOpenOverlay)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Battery exemption (optional)", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenBattery) { Text("Open") }
        }
        // Uninstall protection differs from the rows above: when it's on we offer a "Turn off"
        // action (an app can always deactivate its own device admin). This control lives only in
        // the not-armed setup UI, and Settings is force-blocked while armed, so protection can't be
        // peeled off mid-lock from here. The label takes weight(1f) so the longer text wraps instead
        // of shoving the button off-screen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasUninstallProtection) "Uninstall protection ✓"
                else "Uninstall protection (recommended)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasUninstallProtection) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (hasUninstallProtection) {
                OutlinedButton(onClick = onDisableUninstallProtection) { Text("Turn off") }
            } else {
                OutlinedButton(onClick = onEnableUninstallProtection) { Text("Enable") }
            }
        }
        TextButton(onClick = onRefresh) { Text("I've granted these — refresh") }
    }
}

/**
 * @brief One permission row: a label (with a ✓ when granted) and a Grant button when not.
 * @param label The permission's display name.
 * @param granted Whether it's currently granted.
 * @param onOpen Opens the relevant settings screen.
 */
@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (granted) "$label ✓" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        if (!granted) OutlinedButton(onClick = onOpen) { Text("Grant") }
    }
}

/**
 * @brief Card for setting or changing the partner passcode.
 * @param hasPasscode Whether a passcode already exists (changes the labels shown).
 * @param onSetPasscode Called with the entered passcode when the user saves.
 */
@Composable
private fun PasscodeCard(hasPasscode: Boolean, onSetPasscode: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    SectionCard(if (hasPasscode) "Partner passcode ✓" else "Partner passcode") {
        Text(
            "Your partner sets this. It's stored only as a salted hash and is required to " +
                "unlock.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(if (hasPasscode) "Change passcode" else "Set passcode") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSetPasscode(code); code = "" },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save passcode") }
    }
}

/**
 * @brief Card summarising how many apps are blocked, with a button to open the picker.
 * @param selectedCount Number of apps currently selected.
 * @param onOpenAppPicker Opens the app picker.
 */
@Composable
private fun BlocklistCard(
    selectedCount: Int,
    onOpenAppPicker: () -> Unit
) {
    SectionCard("Blocked apps") {
        Text(
            text = when (selectedCount) {
                0 -> "No apps selected yet."
                1 -> "1 app selected."
                else -> "$selectedCount apps selected."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onOpenAppPicker,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Select apps to block") }
    }
}

/**
 * @brief Full-screen list of installed apps with a checkbox each.
 *
 * @details Shown on top of the home screen when the user taps "Select apps to block", and
 * dismissed with Done or the system back button. As its own screen (not nested in the home's
 * scroll) it can use a lazy list. The hardware/gesture back action is routed to closing the
 * picker rather than exiting the app.
 *
 * @param installedApps The apps to list.
 * @param blockedPackages The package names currently blocked (rendered as checked).
 * @param onToggleApp Called with a package and its new checked state.
 * @param onClose Closes the picker.
 * @param modifier Layout modifier.
 */
@Composable
private fun AppPickerScreen(
    installedApps: List<InstalledApp>,
    blockedPackages: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Route the hardware/gesture back action to closing the picker, not exiting the app.
    BackHandler(onBack = onClose)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Select apps to block", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("Done") }
        }
        if (installedApps.isEmpty()) {
            Text(
                "Loading installed apps…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                items(installedApps, key = { it.packageName }) { appInfo ->
                    AppPickerRow(
                        app = appInfo,
                        checked = appInfo.packageName in blockedPackages,
                        onToggleApp = onToggleApp
                    )
                }
            }
        }
    }
}

/**
 * @brief One row in the app picker: icon, label, and a checkbox; tapping the row toggles it.
 * @param app The app to display.
 * @param checked Whether it's currently blocked.
 * @param onToggleApp Called with the package and its new checked state.
 */
@Composable
private fun AppPickerRow(
    app: InstalledApp,
    checked: Boolean,
    onToggleApp: (String, Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleApp(app.packageName, !checked) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { isChecked -> onToggleApp(app.packageName, isChecked) }
        )
    }
}

/**
 * @brief Card showing up to the 15 most recent accountability events with their timestamps.
 * @param events The recent events, newest first.
 */
@Composable
private fun EventLogCard(events: List<BlockEvent>) {
    SectionCard("Recent activity") {
        if (events.isEmpty()) {
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
            events.take(15).forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(prettyType(event.type), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatter.format(Date(event.at)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                event.packageName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * @brief Reusable titled card that wraps arbitrary content.
 * @param title The card's heading.
 * @param content The composable body, passed as a trailing lambda.
 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
    Spacer(Modifier.height(0.dp))
}

/**
 * @brief Maps a @ref com.kempt.app.data.BlockEvent type constant to a human-readable label for the log.
 * @param type The stored event-type string.
 * @return A display string, or @p type itself if unrecognised.
 */
private fun prettyType(type: String): String = when (type) {
    BlockEvent.LOCK_ARMED -> "Locked down"
    BlockEvent.LOCK_DISARMED -> "Unlocked"
    BlockEvent.BLOCK_ENFORCED -> "Blocked app opened"
    BlockEvent.UNLOCK_SUCCESS -> "Unlocked with passcode"
    BlockEvent.UNLOCK_FAILED -> "Wrong passcode"
    BlockEvent.USAGE_ACCESS_LOST -> "Usage access revoked (tamper)"
    BlockEvent.BOOT_REARM -> "Re-armed after reboot"
    BlockEvent.DEVICE_ADMIN_ENABLED -> "Uninstall protection on"
    BlockEvent.DEVICE_ADMIN_DISABLE_REQUESTED -> "Uninstall protection removal attempted (tamper)"
    BlockEvent.DEVICE_ADMIN_DISABLED -> "Uninstall protection off"
    else -> type
}
