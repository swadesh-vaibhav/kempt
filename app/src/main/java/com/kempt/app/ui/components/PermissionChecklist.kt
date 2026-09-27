/**
 * @file
 * @brief Live special-access permission status (auto-refreshed on resume) and a shared permission row.
 */
package com.kempt.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kempt.app.util.Permissions

/**
 * @brief Immutable snapshot of Kempt's four special-access grants.
 * @property usageAccess Whether "Usage access" is granted (required for the monitor).
 * @property overlay Whether "Draw over other apps" is granted (required for the lock overlay).
 * @property battery Whether Kempt is exempt from battery optimization (optional).
 * @property deviceAdmin Whether uninstall protection (device admin) is active (optional).
 */
data class PermissionStatus(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
    val deviceAdmin: Boolean
)

/**
 * @brief Reads the four special-access grants and re-reads them every time the app is resumed.
 *
 * @details These permissions are toggled by the user in system Settings — a different process — so
 * their values can change while Kempt is in the background. To reflect a grant the instant the user
 * returns, this observes the current screen's lifecycle:
 * @code
 * DisposableEffect(owner) {           // runs once; its cleanup runs when the composable leaves
 *     val obs = LifecycleEventObserver { _, e -> if (e == ON_RESUME) tick++ }
 *     owner.lifecycle.addObserver(obs)
 *     onDispose { owner.lifecycle.removeObserver(obs) }  // avoid leaking the observer
 * }
 * @endcode
 * Each @c ON_RESUME bumps @c tick, and the @c remember(tick) block below re-runs the checks — so
 * returning from a Settings screen refreshes the ✓ marks with no manual "refresh" tap.
 *
 * @note @c DisposableEffect is the Compose primitive for side effects that need cleanup;
 * @c LocalLifecycleOwner is the ambient lifecycle owner (the Activity here).
 *
 * @param refreshSignal An optional value; changing it forces an immediate re-read. Useful after an
 * in-app change that doesn't cause a resume (e.g. turning off the device admin from within Kempt).
 * @return The current @ref PermissionStatus, recomputed on each resume or when @p refreshSignal changes.
 */
@Composable
fun rememberPermissionStatus(refreshSignal: Any? = null): PermissionStatus {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(tick, refreshSignal) {
        PermissionStatus(
            usageAccess = Permissions.hasUsageAccess(context),
            overlay = Permissions.canDrawOverlays(context),
            battery = Permissions.isIgnoringBatteryOptimizations(context),
            deviceAdmin = Permissions.isDeviceAdminActive(context)
        )
    }
}

/**
 * @brief One permission row: a label (with a ✓ and primary colour when granted) plus a trailing action.
 *
 * @details A slot API — the @p trailing lambda holds whatever control the caller wants on the right
 * (a Grant button, a Grant + Skip pair, an Enable/Turn-off button, or nothing once granted). The label
 * takes @c weight(1f) so long text wraps instead of pushing the action off-screen.
 *
 * @param label The permission's display name.
 * @param granted Whether it's currently granted (drives the ✓ and colour).
 * @param modifier Layout modifier applied to the row.
 * @param trailing The right-hand action content.
 */
@Composable
fun PermissionRow(
    label: String,
    granted: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (granted) "$label ✓" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}
