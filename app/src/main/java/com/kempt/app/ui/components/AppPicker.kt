/**
 * @file
 * @brief Reusable installed-app picker: a full-screen variant and an embeddable list variant.
 */
package com.kempt.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kempt.app.ui.InstalledApp

/**
 * @brief Full-screen list of installed apps with a checkbox each, plus a header and Done button.
 *
 * @details Used when the picker is shown as its own screen (e.g. from the Apps tab). The
 * hardware/gesture back action is routed to closing the picker via @c BackHandler — a composable that
 * intercepts the system back press while it is in the composition.
 *
 * @param installedApps The apps to list.
 * @param blockedPackages The package names currently blocked (rendered as checked).
 * @param onToggleApp Called with a package and its new checked state.
 * @param onClose Closes the picker.
 * @param modifier Layout modifier.
 */
@Composable
fun AppPickerScreen(
    installedApps: List<InstalledApp>,
    blockedPackages: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        AppPickerList(
            installedApps = installedApps,
            blockedPackages = blockedPackages,
            onToggleApp = onToggleApp,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * @brief The bare scrolling list of apps (no header) so it can be embedded in other screens.
 *
 * @details Extracted from @ref AppPickerScreen so the onboarding "choose apps" step can reuse the same
 * list under its own wizard chrome. Shows a loading line until @p installedApps is populated (the list
 * is loaded off the main thread in the ViewModel).
 *
 * @param installedApps The apps to list.
 * @param blockedPackages The package names currently blocked (rendered as checked).
 * @param onToggleApp Called with a package and its new checked state.
 * @param modifier Layout modifier.
 */
@Composable
fun AppPickerList(
    installedApps: List<InstalledApp>,
    blockedPackages: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (installedApps.isEmpty()) {
        Text(
            "Loading installed apps…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 20.dp)
        )
    } else {
        LazyColumn(
            modifier = modifier,
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
