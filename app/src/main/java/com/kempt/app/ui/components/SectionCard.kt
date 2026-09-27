/**
 * @file
 * @brief A small reusable titled-card container used across the Kempt screens.
 */
package com.kempt.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * @brief Reusable titled card that wraps arbitrary content.
 *
 * @details A Compose "slot" API: the @p content parameter is a @c @Composable lambda, so callers pass
 * the card body as a trailing lambda (e.g. @c SectionCard("Title") { ... }) and this composable draws
 * the card chrome (elevation, padding, title) around it.
 *
 * @param title The card's heading.
 * @param modifier Layout modifier applied to the card.
 * @param content The composable body, passed as a trailing lambda.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
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
}
