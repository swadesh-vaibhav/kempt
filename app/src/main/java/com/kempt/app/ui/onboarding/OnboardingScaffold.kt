/**
 * @file
 * @brief Shared chrome for the first-run onboarding wizard steps (title, progress, advance button).
 */
package com.kempt.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * @brief Renders the common frame around each onboarding step: heading, "Step X of Y", the step's body,
 * and a bottom advance button that is only enabled once the step's condition is met.
 *
 * @details A slot API — @p content is the step-specific body, drawn in a scrolling column between the
 * header and the advance button. Keeping this frame in one place means the three steps stay visually
 * consistent and only implement their own body + advance rule.
 *
 * @param title The step heading.
 * @param step The 1-based index of this step.
 * @param totalSteps How many steps the wizard has in total.
 * @param canAdvance Whether the advance button is enabled (the step's completion rule).
 * @param advanceLabel The advance button's caption (e.g. "Continue" or "Finish setup").
 * @param onAdvance Called when the user taps the enabled advance button.
 * @param modifier Layout modifier from the host.
 * @param content The step's body content, run inside a bounded @c ColumnScope that takes the leftover
 * height. It is intentionally NOT auto-scrolled here: each step decides its own scrolling (a step with
 * short content wraps it in a @c verticalScroll column; the app-picker step drops a @c LazyColumn with
 * @c weight(1f) straight in — a lazy list must not be nested in a parent @c verticalScroll).
 */
@Composable
fun OnboardingScaffold(
    title: String,
    step: Int,
    totalSteps: Int,
    canAdvance: Boolean,
    advanceLabel: String,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Draw clear of the status bar / gesture nav (the app is edge-to-edge), and let the layout
            // shrink above the on-screen keyboard so the advance button stays reachable.
            .systemBarsPadding()
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Step $step of $totalSteps",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)

        // Bounded region that takes all leftover height, keeping the advance button pinned below.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )

        Button(
            onClick = onAdvance,
            enabled = canAdvance,
            modifier = Modifier.fillMaxWidth()
        ) { Text(advanceLabel) }
    }
}
