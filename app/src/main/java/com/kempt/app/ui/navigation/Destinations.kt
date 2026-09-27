/**
 * @file
 * @brief The navigation routes and bottom-tab identifiers for Kempt's Compose navigation graph.
 */
package com.kempt.app.ui.navigation

/**
 * @brief The closed set of navigation destinations, each carrying its route string.
 *
 * @details A Kotlin @c sealed class is a hierarchy whose subtypes are all known at compile time — here,
 * every place the @c NavController can navigate to. Centralising the route strings (rather than typing
 * @c "main" etc. at each call site) makes them typo-proof and refactor-safe. @c data object is a
 * singleton with a generated @c toString/@c equals; we only need one instance of each route.
 *
 * @property route The string the navigation library matches on.
 */
sealed class Dest(val route: String) {
    /** @brief The onboarding nested graph (its own start is @ref OnbPermissions). */
    data object Onboarding : Dest("onboarding")

    /** @brief Onboarding step 1: grant permissions one at a time. */
    data object OnbPermissions : Dest("onboarding/permissions")

    /** @brief Onboarding step 2: choose apps to block. */
    data object OnbApps : Dest("onboarding/apps")

    /** @brief Onboarding step 3: set the partner passcode, then finish. */
    data object OnbPasscode : Dest("onboarding/passcode")

    /** @brief The main app (tabbed scaffold, or the locked screen while armed). */
    data object Main : Dest("main")

    /** @brief The permission-management screen reached from the hamburger drawer. */
    data object SettingsPermissions : Dest("settings/permissions")
}

/**
 * @brief The three bottom-navigation tabs of the main screen.
 * @details Tab selection is plain in-composable state (no per-tab back stack needed), so the tabs are
 * an @c enum rather than separate nav destinations.
 */
enum class MainTab { APPS, LOCKDOWN, PARTNER }
