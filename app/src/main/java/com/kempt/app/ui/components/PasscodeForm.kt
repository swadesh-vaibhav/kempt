/**
 * @file
 * @brief Reusable card for setting or changing the partner passcode.
 */
package com.kempt.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * @brief Card for setting or changing the partner passcode.
 *
 * @details Holds the in-progress text in local @c remember state and clears it after saving. The
 * plaintext is handed to @p onSetPasscode, which stores only a salted hash (see
 * @ref com.kempt.app.data.LockStateStore.setPasscode) — it is never persisted or logged in the clear.
 *
 * @param hasPasscode Whether a passcode already exists (changes the labels shown).
 * @param onSetPasscode Called with the entered passcode when the user saves.
 * @param modifier Layout modifier applied to the card.
 */
@Composable
fun PasscodeForm(
    hasPasscode: Boolean,
    onSetPasscode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var code by remember { mutableStateOf("") }
    SectionCard(if (hasPasscode) "Partner passcode ✓" else "Partner passcode", modifier = modifier) {
        Text(
            "Your partner sets this. It's stored only as a salted hash and is required to unlock.",
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
