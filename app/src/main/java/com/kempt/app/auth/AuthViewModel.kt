/**
 * @file
 * @brief ViewModel exposing the auth-gate state and the sign-in/out actions to the UI.
 */
package com.kempt.app.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @brief The three states the top-level UI gate can be in.
 *
 * @details A Kotlin @c sealed @c interface: the set of implementations is fixed and known here, so a
 * @c when over it is exhaustive (no @c else needed). @c data @c object is a singleton case;
 * @c SignedIn carries data.
 */
sealed interface AuthState {
    /** @brief The current user hasn't been read yet — show a brief splash, not the sign-in screen. */
    data object Loading : AuthState

    /** @brief No user is signed in — show the sign-in screen. */
    data object SignedOut : AuthState

    /**
     * @brief A user is signed in — show the app.
     * @property uid The Firebase uid that scopes this user's backend data.
     * @property displayName The Google display name, if any.
     */
    data class SignedIn(val uid: String, val displayName: String?) : AuthState
}

/**
 * @brief Drives the auth gate: publishes @ref AuthState and runs sign-in/out.
 *
 * @details @c @HiltViewModel lets Hilt supply the @ref AuthRepository. The UI reads @ref authState
 * for which screen to show, and @ref signingIn / @ref signInError to render the sign-in button's
 * progress and any error.
 *
 * @param authRepository The auth source of truth.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    /**
     * @brief The gate state, derived from the repository's user stream.
     * @details @c map turns each @c FirebaseUser? into an @ref AuthState; @c stateIn converts the cold
     * flow into a hot @c StateFlow the UI can read synchronously, starting at @ref AuthState.Loading
     * until the first real value arrives. @c WhileSubscribed(5000) keeps the upstream listener alive
     * for 5s after the last collector leaves, so a config change (rotation) doesn't tear it down.
     */
    val authState: StateFlow<AuthState> =
        authRepository.authState
            .map { user ->
                if (user == null) AuthState.SignedOut
                else AuthState.SignedIn(user.uid, user.displayName)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthState.Loading)

    /** @brief @c true while a sign-in attempt is in flight (drives the button spinner). */
    var signingIn by mutableStateOf(false)
        private set

    /** @brief The last sign-in error message, or @c null. Cleared when a new attempt starts. */
    var signInError by mutableStateOf<String?>(null)
        private set

    /**
     * @brief Starts a Google sign-in.
     * @param activityContext An Activity context (the account picker draws over the current screen).
     */
    fun signIn(activityContext: Context) {
        viewModelScope.launch {
            signingIn = true
            signInError = null
            val result = authRepository.signInWithGoogle(activityContext)
            signingIn = false
            result.exceptionOrNull()?.let { signInError = it.message ?: "Sign-in failed" }
            // On success, authState flips to SignedIn on its own via the auth-state listener.
        }
    }

    /** @brief Signs the current user out; @ref authState will flip to @ref AuthState.SignedOut. */
    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}
