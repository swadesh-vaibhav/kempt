/**
 * @file
 * @brief The root composable: owns the shared ViewModels, gates on sign-in, then on onboarding.
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
import com.kempt.app.auth.AuthState
import com.kempt.app.auth.AuthViewModel
import com.kempt.app.ui.auth.SignInScreen
import com.kempt.app.ui.navigation.Dest
import com.kempt.app.ui.navigation.KemptNavHost

/**
 * @brief Top of the Compose tree: gates on authentication, then onboarding, then builds the graph.
 *
 * @details Two outer gates wrap the app, outermost first:
 * - **Sign-in.** @ref AuthViewModel.authState starts at @ref AuthState.Loading (brief splash), then
 *   resolves to @ref AuthState.SignedOut (show @ref SignInScreen) or @ref AuthState.SignedIn (show the
 *   app). Signing in is what turns the Firestore backend from inert to live — every write needs the uid.
 * - **Onboarding.** Only once signed in: @ref HomeViewModel.onboardingComplete is @c null until read
 *   (splash), then picks @ref Dest.Onboarding or @ref Dest.Main. Keeping the start destination in a
 *   @c remember avoids re-showing the wizard when the flag flips on finish.
 *
 * Both ViewModels are obtained here with @c hiltViewModel(), so each is Activity-scoped and shared —
 * @ref HomeViewModel in particular is passed down into @ref KemptNavHost, never re-fetched per screen.
 *
 * Notification permission (Android 13+) is requested once, up front, so service/accountability
 * notifications can show.
 *
 * @param viewModel The shared home ViewModel (Hilt-provided; defaulted so previews/tests can inject).
 * @param authViewModel The auth-gate ViewModel (Hilt-provided).
 */
@Composable
fun KemptApp(
    viewModel: HomeViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
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

    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    when (authState) {
        // Current user not read yet — brief splash, don't flash the sign-in screen.
        AuthState.Loading -> Box(Modifier.fillMaxSize())

        // No user — prompt sign-in. Success flips authState to SignedIn and this recomposes.
        AuthState.SignedOut -> SignInScreen(
            signingIn = authViewModel.signingIn,
            errorMessage = authViewModel.signInError,
            onSignInClick = { authViewModel.signIn(context) },
        )

        // Signed in — fall through to the onboarding/main gate.
        is AuthState.SignedIn -> {
            val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()

            when (val done = onboardingComplete) {
                null -> Box(Modifier.fillMaxSize()) // brief splash while the flag is read from DataStore
                else -> {
                    val navController = rememberNavController()
                    // Capture the start once. When onboarding finishes, the flag flips false -> true and
                    // this recomposes; keeping the start fixed stops NavHost from rebuilding its graph —
                    // the wizard's explicit navigate(main) already moved the user.
                    val startDestination = remember {
                        if (done) Dest.Main.route else Dest.Onboarding.route
                    }
                    KemptNavHost(
                        navController = navController,
                        viewModel = viewModel,
                        startDestination = startDestination,
                    )
                }
            }
        }
    }
}
