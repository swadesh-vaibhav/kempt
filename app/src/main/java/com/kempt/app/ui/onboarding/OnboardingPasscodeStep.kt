/**
 * @file
 * @brief Onboarding step 3: set the partner passcode (entered twice to confirm), then finish setup.
 */
package com.kempt.app.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * @brief Step 3 of onboarding: choose the partner passcode.
 *
 * @details Requires the code to be typed twice and to match before "Finish setup" enables — a confirm
 * field guards against a typo locking the user out (only the partner's passcode can unlock a lockdown).
 * The plaintext is passed to @p onSetPasscode, which stores only a salted hash; it is never persisted
 * in the clear.
 *
 * @param onSetPasscode Called with the confirmed passcode to store it.
 * @param onFinish Called after saving to mark onboarding complete and enter the main app.
 */
@Composable
fun OnboardingPasscodeStep(
    onSetPasscode: (String) -> Unit,
    onFinish: () -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val matches = code.isNotBlank() && code == confirm
    val mismatch = confirm.isNotBlank() && code != confirm

    OnboardingScaffold(
        title = "Set the partner passcode",
        step = 3,
        totalSteps = 3,
        canAdvance = matches,
        advanceLabel = "Finish setup",
        onAdvance = {
            onSetPasscode(code)
            onFinish()
        }
    ) {
        Text(
            "Your accountability partner chooses this code. You'll need them to enter it to end a " +
                "lockdown. It's stored only as a salted hash.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Passcode") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm passcode") },
            singleLine = true,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (mismatch) {
            Text(
                "The two entries don't match.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
