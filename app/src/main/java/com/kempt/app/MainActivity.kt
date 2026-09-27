/**
 * @file
 * @brief The single activity hosting Kempt's Compose UI.
 */
package com.kempt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kempt.app.ui.KemptApp
import com.kempt.app.ui.theme.KemptTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * @brief The app's only activity; installs the Compose content tree.
 *
 * @details @c @AndroidEntryPoint enables Hilt injection, which is what lets @c hiltViewModel() inside
 * @ref com.kempt.app.ui.KemptApp obtain a @ref com.kempt.app.ui.HomeViewModel scoped to this activity.
 * All UI — the onboarding wizard, the tabbed main screen, and the locked screen — lives under
 * @ref com.kempt.app.ui.KemptApp; this class only wires it up.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * @brief Enables edge-to-edge drawing and installs the Compose content tree.
     * @param savedInstanceState The standard saved-state bundle (unused here).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KemptTheme {
                KemptApp()
            }
        }
    }
}
