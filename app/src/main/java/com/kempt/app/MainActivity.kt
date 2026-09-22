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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
            onRefreshPermissions = { permissionTick++ },
            onSetPasscode = viewModel::setPasscode,
            onOpenAppPicker = { showAppPicker = true },
            onLockDown = viewModel::lockDown,
            onDisarm = viewModel::disarm,
            modifier = modifier
        )
    }
}

@Composable
private fun HomeScreen(
    state: HomeUiState,
    selectedAppCount: Int,
    hasUsageAccess: Boolean,
    hasOverlay: Boolean,
    onRefreshPermissions: () -> Unit,
    onSetPasscode: (String) -> Unit,
    onOpenAppPicker: () -> Unit,
    onLockDown: () -> Unit,
    onDisarm: (String, (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
                onOpenUsageAccess = {
                    context.startActivity(Permissions.usageAccessSettings())
                },
                onOpenOverlay = {
                    context.startActivity(Permissions.overlaySettings(context))
                },
                onOpenBattery = {
                    context.startActivity(Permissions.batteryOptimizationSettings())
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

@Composable
private fun PermissionsCard(
    hasUsageAccess: Boolean,
    hasOverlay: Boolean,
    onOpenUsageAccess: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenBattery: () -> Unit,
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
        TextButton(onClick = onRefresh) { Text("I've granted these — refresh") }
    }
}

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
 * Full-screen list of installed apps with a checkbox each. Shown on top of the home screen
 * when the user taps "Select apps to block", and dismissed with Done or the system back
 * button. As its own screen (not nested in the home's scroll) it can use a lazy list.
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

private fun prettyType(type: String): String = when (type) {
    BlockEvent.LOCK_ARMED -> "Locked down"
    BlockEvent.LOCK_DISARMED -> "Unlocked"
    BlockEvent.BLOCK_ENFORCED -> "Blocked app opened"
    BlockEvent.UNLOCK_SUCCESS -> "Unlocked with passcode"
    BlockEvent.UNLOCK_FAILED -> "Wrong passcode"
    BlockEvent.USAGE_ACCESS_LOST -> "Usage access revoked (tamper)"
    BlockEvent.BOOT_REARM -> "Re-armed after reboot"
    else -> type
}
