/**
 * @file
 * @brief Onboarding step 1: grant the special-access permissions one at a time.
 */
package com.kempt.app.ui.onboarding

import android.content.Context
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kempt.app.ui.components.PermissionRow
import com.kempt.app.ui.components.rememberPermissionStatus
import com.kempt.app.util.Permissions

/**
 * @brief The four special-access permissions Kempt walks the user through, in grant order.
 * @details @c USAGE and @c OVERLAY are required for a lock to work; @c BATTERY and @c DEVICE_ADMIN are
 * optional hardening the user may skip.
 */
private enum class PermItem { USAGE, OVERLAY, BATTERY, DEVICE_ADMIN }

/** @brief The fixed order permissions are presented in. */
private val ORDER = listOf(PermItem.USAGE, PermItem.OVERLAY, PermItem.BATTERY, PermItem.DEVICE_ADMIN)

/** @brief The permissions that must be granted (never skippable) before the wizard can advance. */
private val REQUIRED = setOf(PermItem.USAGE, PermItem.OVERLAY)

/**
 * @brief Step 1 of onboarding: a progressive checklist that reveals one "Grant" button at a time.
 *
 * @details Each already-granted permission shows a ✓; the first not-yet-satisfied permission shows its
 * action (Grant/Enable, plus Skip for the optional two); later permissions are shown greyed with no
 * action, so exactly one is actionable at a time. Grants happen in system Settings; when the user
 * returns, @ref rememberPermissionStatus re-checks on resume, the ✓ appears, and the active pointer
 * moves to the next item automatically. "Continue" enables only once every permission is granted or
 * skipped (the required two must actually be granted).
 *
 * @param onContinue Called to advance to the app-selection step.
 */
@Composable
fun OnboardingPermissionsStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    val status = rememberPermissionStatus()

    // The two optional permissions can be skipped; rememberSaveable keeps the choice across rotation.
    var skipBattery by rememberSaveable { mutableStateOf(false) }
    var skipDeviceAdmin by rememberSaveable { mutableStateOf(false) }

    // Device admin is activated on a system consent screen that returns a result; launching it for a
    // result means the status re-check on resume will pick up the change with no manual refresh.
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* no-op: rememberPermissionStatus refreshes on ON_RESUME */ }

    fun granted(item: PermItem): Boolean = when (item) {
        PermItem.USAGE -> status.usageAccess
        PermItem.OVERLAY -> status.overlay
        PermItem.BATTERY -> status.battery
        PermItem.DEVICE_ADMIN -> status.deviceAdmin
    }

    // "done" = granted, or (for the optional two) explicitly skipped. Required items can only be done
    // by actually granting them.
    fun done(item: PermItem): Boolean = granted(item) ||
        (item == PermItem.BATTERY && skipBattery) ||
        (item == PermItem.DEVICE_ADMIN && skipDeviceAdmin)

    val activeIndex = ORDER.indexOfFirst { !done(it) }   // -1 once every item is granted-or-skipped
    val allDone = activeIndex == -1

    OnboardingScaffold(
        title = "Grant permissions",
        step = 1,
        totalSteps = 3,
        canAdvance = allDone,
        advanceLabel = "Continue",
        onAdvance = onContinue
    ) {
        Text(
            "Kempt needs a couple of permissions to lock apps. Grant them one at a time — the two " +
                "optional ones can be skipped.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ORDER.forEachIndexed { index, item ->
            val isGranted = granted(item)
            val isActive = index == activeIndex
            val optional = item !in REQUIRED
            PermissionRow(label = labelFor(item), granted = isGranted) {
                when {
                    isGranted -> {} // ✓ only, no action
                    isActive -> {
                        OutlinedButton(onClick = { open(item, context, deviceAdminLauncher) }) {
                            Text(if (item == PermItem.DEVICE_ADMIN) "Enable" else "Grant")
                        }
                        if (optional) {
                            TextButton(onClick = {
                                if (item == PermItem.BATTERY) skipBattery = true
                                else skipDeviceAdmin = true
                            }) { Text("Skip") }
                        }
                    }
                    else -> {} // later rows: shown for context, but not yet actionable
                }
            }
        }
    }
}

/** @brief The user-visible label for a permission item (with an "(optional)" hint for the skippable two). */
private fun labelFor(item: PermItem): String = when (item) {
    PermItem.USAGE -> "Usage access"
    PermItem.OVERLAY -> "Draw over other apps"
    PermItem.BATTERY -> "Battery exemption (optional)"
    PermItem.DEVICE_ADMIN -> "Uninstall protection (optional)"
}

/**
 * @brief Opens the correct system screen to grant a given permission.
 * @param item Which permission to grant.
 * @param context Context used to start the settings activity / build the intents.
 * @param deviceAdminLauncher Result launcher for the device-admin consent screen.
 */
private fun open(
    item: PermItem,
    context: Context,
    deviceAdminLauncher: ManagedActivityResultLauncher<android.content.Intent, ActivityResult>
) {
    when (item) {
        PermItem.USAGE -> context.startActivity(Permissions.usageAccessSettings())
        PermItem.OVERLAY -> context.startActivity(Permissions.overlaySettings(context))
        PermItem.BATTERY -> context.startActivity(Permissions.batteryOptimizationSettings())
        PermItem.DEVICE_ADMIN -> deviceAdminLauncher.launch(Permissions.addDeviceAdminIntent(context))
    }
}
