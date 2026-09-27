/**
 * @file
 * @brief The root composable: owns the shared ViewModel, requests notifications, and gates the start.
 */
package com.kempt.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.kempt.app.ui.navigation.Dest
import com.kempt.app.ui.navigation.KemptNavHost

/**
 * @brief Top of the Compose tree: chooses the start destination and builds the navigation graph.
 *
 * @details Responsibilities:
 * - **One ViewModel for the whole app.** @c hiltViewModel() is called exactly here, so the instance is
 *   scoped to the hosting Activity and shared by every screen (the installed-app list loads once). It is
 *   passed down into @ref KemptNavHost — never re-fetched inside a nav destination.
 * - **Notification permission.** On Android 13+ (@c TIRAMISU) we request @c POST_NOTIFICATIONS once, so
 *   the foreground-service and accountability notifications can show.
 * - **No wrong-screen flash.** @ref HomeViewModel.onboardingComplete starts as @c null ("not read
 *   yet"); while it's null we render an empty @c Box, then build the @c NavHost with the correct start
 *   once the real value arrives. This avoids briefly showing the wizard to returning users.
 *
 * @param viewModel The shared home ViewModel (Hilt-provided; defaulted so previews/tests can inject).
 */
@Composable
fun KemptApp(viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored; notifications are best-effort */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()

    when (val done = onboardingComplete) {
        null -> Box(Modifier.fillMaxSize()) // brief splash while the flag is read from DataStore
        else -> {
            val navController = rememberNavController()
            // Capture the start once. When onboarding finishes, the flag flips false -> true and this
            // branch recomposes; keeping the start fixed stops NavHost from rebuilding its graph — the
            // wizard's explicit navigate(main) already moved the user.
            val startDestination = remember {
                if (done) Dest.Main.route else Dest.Onboarding.route
            }
            KemptNavHost(
                navController = navController,
                viewModel = viewModel,
                startDestination = startDestination
            )
        }
    }
}
