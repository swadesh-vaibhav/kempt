/**
 * @file
 * @brief The recent-activity (accountability event) log card and its label mapping.
 */
package com.kempt.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kempt.app.data.BlockEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @brief Card showing up to the 15 most recent accountability events with their timestamps.
 * @param events The recent events, newest first.
 * @param modifier Layout modifier applied to the card.
 */
@Composable
fun EventLogCard(events: List<BlockEvent>, modifier: Modifier = Modifier) {
    SectionCard("Recent activity", modifier = modifier) {
        if (events.isEmpty()) {
            Text(
                "Nothing yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // remember caches the formatter across recompositions so it isn't rebuilt each frame.
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
