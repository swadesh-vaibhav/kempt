/**
 * @file
 * @brief The Compose navigation graph wiring the onboarding wizard, the main app, and settings.
 */
package com.kempt.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.kempt.app.ui.HomeViewModel
import com.kempt.app.ui.main.MainScaffold
import com.kempt.app.ui.onboarding.OnboardingPasscodeStep
import com.kempt.app.ui.onboarding.OnboardingPermissionsStep
import com.kempt.app.ui.onboarding.OnboardingSelectAppsStep
import com.kempt.app.ui.settings.PermissionsScreen

/**
 * @brief Builds the app's navigation graph and connects each destination to the shared ViewModel.
 *
 * @details The graph has three areas:
 * - a nested @c "onboarding" graph (permissions → apps → passcode) used on first run;
 * - @c "main", the tabbed shell;
 * - @c "settings/permissions", reached from the drawer.
 *
 * The single @ref com.kempt.app.ui.HomeViewModel is passed in (obtained once, Activity-scoped, in
 * @ref com.kempt.app.ui.KemptApp) and shared by every destination — deliberately NOT re-fetched with
 * @c hiltViewModel() inside each @c composable block, which would create a separate instance per
 * back-stack entry and reload the installed-app list.
 *
 * The state is collected once here and threaded down as plain values + lambdas, keeping every screen a
 * stateless, previewable composable.
 *
 * @param navController The controller driving navigation.
 * @param viewModel The shared home ViewModel.
 * @param startDestination Either @ref Dest.Onboarding or @ref Dest.Main, decided from the persisted
 * onboarding flag before this composable is built.
 */
@Composable
fun KemptNavHost(
    navController: NavHostController,
    viewModel: HomeViewModel,
    startDestination: String
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val blocked = remember(state.rules) { state.rules.map { it.packageName }.toSet() }

    NavHost(navController = navController, startDestination = startDestination) {
        navigation(
            startDestination = Dest.OnbPermissions.route,
            route = Dest.Onboarding.route
        ) {
            composable(Dest.OnbPermissions.route) {
                OnboardingPermissionsStep(
                    onContinue = { navController.navigate(Dest.OnbApps.route) }
                )
            }
            composable(Dest.OnbApps.route) {
                OnboardingSelectAppsStep(
                    installedApps = installedApps,
                    blockedPackages = blocked,
                    onToggleApp = viewModel::setAppBlocked,
                    onContinue = { navController.navigate(Dest.OnbPasscode.route) }
                )
            }
            composable(Dest.OnbPasscode.route) {
                OnboardingPasscodeStep(
                    onSetPasscode = viewModel::setPasscode,
                    onFinish = {
                        viewModel.completeOnboarding()
                        navController.navigate(Dest.Main.route) {
                            // Clear the whole onboarding graph so Back can't return to the wizard.
                            popUpTo(Dest.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }
        }

        composable(Dest.Main.route) {
            MainScaffold(
                state = state,
                installedApps = installedApps,
                blockedPackages = blocked,
                onToggleApp = viewModel::setAppBlocked,
                onSetPasscode = viewModel::setPasscode,
                onLockDown = viewModel::lockDown,
                onDisarm = viewModel::disarm,
                onOpenPermissions = { navController.navigate(Dest.SettingsPermissions.route) }
            )
        }

        composable(Dest.SettingsPermissions.route) {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
    }
}
