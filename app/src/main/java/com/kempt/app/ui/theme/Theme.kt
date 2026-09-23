/**
 * @file
 * @brief Jetpack Compose Material 3 theme wrapper for the app.
 */
package com.kempt.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** @brief Fallback light color scheme used before Android 12 or when dynamic color is off. */
private val LightColors = lightColorScheme()

/** @brief Fallback dark color scheme used before Android 12 or when dynamic color is off. */
private val DarkColors = darkColorScheme()

/**
 * @brief Applies the app's Material 3 color scheme to its content.
 *
 * @details Picks a color scheme in priority order: dynamic (wallpaper-derived) colors on
 * Android 12+ when @p dynamicColor is set, otherwise a static dark or light scheme. The
 * chosen scheme is handed to Material 3's @c MaterialTheme, which exposes it to every
 * composable inside @p content.
 *
 * @param darkTheme Whether to use dark colors; defaults to the current system setting.
 * @param dynamicColor Whether to use Android 12+ dynamic colors when available.
 * @param content The composable UI to render inside this theme.
 */
@Composable
fun KemptTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
