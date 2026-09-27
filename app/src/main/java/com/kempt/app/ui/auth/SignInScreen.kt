/**
 * @file
 * @brief The sign-in screen shown while no user is authenticated.
 */
package com.kempt.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kempt.app.ui.theme.KemptTheme

/**
 * @brief Full-screen sign-in prompt: app name, tagline, and a "Sign in with Google" button.
 *
 * @details Stateless and previewable, matching the rest of the UI — all state (in-flight, error) and
 * the click action are passed in, so this composable just renders. The hosting gate
 * (@ref com.kempt.app.ui.KemptApp) swaps it out automatically once sign-in succeeds.
 *
 * @param signingIn Whether a sign-in is in progress (shows a spinner, disables the button).
 * @param errorMessage An error to display beneath the button, or @c null.
 * @param onSignInClick Invoked when the button is tapped.
 * @param modifier Standard Compose modifier for the root.
 */
@Composable
fun SignInScreen(
    signingIn: Boolean,
    errorMessage: String?,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Kempt",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = "One-tap lockdown — unlocked only by your partner.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onSignInClick,
                enabled = !signingIn,
            ) {
                if (signingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Sign in with Google")
                }
            }

            errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** @brief Design-time preview of the idle sign-in screen. */
@Preview(showBackground = true)
@Composable
private fun SignInScreenPreview() {
    KemptTheme {
        SignInScreen(signingIn = false, errorMessage = null, onSignInClick = {})
    }
}
