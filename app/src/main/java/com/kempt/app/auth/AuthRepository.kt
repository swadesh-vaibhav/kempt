/**
 * @file
 * @brief Google Sign-In (via Credential Manager) wired to Firebase Auth, plus an auth-state stream.
 */
package com.kempt.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.kempt.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @brief Single source of truth for who is signed in and how they sign in/out.
 *
 * @details Google Sign-In here uses **Credential Manager** (@c androidx.credentials) with the
 * **Sign in with Google** option — Google's current API, replacing the deprecated
 * @c GoogleSignInClient. The flow is: ask Credential Manager for a Google ID token, then hand that
 * token to Firebase Auth. Firebase becomes the app's identity of record; its @c uid is what the
 * Firestore security rules key every document on.
 *
 * @note @c @Singleton + @c @Inject @c constructor let Hilt build and share one instance. Provided
 * automatically because both parameters are already in the graph.
 *
 * @param appContext Application context, used to create @ref credentialManager and read the web client id.
 * @param auth The Firebase Auth client.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val auth: FirebaseAuth,
) {

    /** @brief The Credential Manager client; created once from the application context. */
    private val credentialManager = CredentialManager.create(appContext)

    /**
     * @brief A cold stream that emits the current @c FirebaseUser (or @c null when signed out),
     * re-emitting on every sign-in/sign-out.
     *
     * @details @c callbackFlow is a Kotlin coroutines builder that adapts a callback-based API into a
     * @c Flow: it registers an @c AuthStateListener, forwards each callback value with @c trySend, and
     * — crucially — @c awaitClose removes the listener when the collector goes away, so nothing leaks.
     * Firebase invokes the listener immediately on registration, so collectors get the current state
     * right away.
     */
    val authState: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** @brief The currently signed-in user, or @c null. A cheap synchronous snapshot. */
    val currentUser: FirebaseUser? get() = auth.currentUser

    /**
     * @brief Runs the full Google → Firebase sign-in and reports success or failure.
     *
     * @details Steps: (1) build a @c GetGoogleIdOption with this project's *web* client id; (2) ask
     * Credential Manager to fetch a credential — this shows the account picker, so it needs an
     * **Activity** context; (3) pull the Google ID token out of the returned credential; (4) exchange
     * it for a Firebase credential and sign in. A @c kotlin.Result carries either @c Unit on success
     * or the thrown exception, so the caller can show a message without a try/catch of its own.
     *
     * @param activityContext An Activity context (the sign-in UI is drawn over the current screen).
     * @return @c Result.success on sign-in, @c Result.failure(cause) otherwise.
     * @warning Requires the console setup (Google provider enabled, this app's SHA-1 registered) and a
     * real @c google_web_client_id; otherwise it fails fast with an explanatory message.
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
        val webClientId = appContext.getString(R.string.google_web_client_id)
        if (webClientId.isBlank() || webClientId.startsWith("PASTE_")) {
            return Result.failure(
                IllegalStateException("google_web_client_id is not set (see res/values/auth.xml)")
            )
        }

        return try {
            // GetSignInWithGoogleOption is the flow meant for an explicit "Sign in with Google"
            // button: it always shows the account picker (and an "add account" path). We use it
            // instead of GetGoogleIdOption, which targets silent/returning sign-in and raises
            // NoCredentialException when it has nothing to auto-offer.
            val signInOption = GetSignInWithGoogleOption.Builder(webClientId).build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                auth.signInWithCredential(firebaseCredential).await()
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
            }
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No credential available", e)
            Result.failure(
                IllegalStateException(
                    "No Google account on this device. Add one in Settings → Accounts, then try again."
                )
            )
        } catch (e: GetCredentialException) {
            // User cancelled, misconfiguration, etc.
            Log.w(TAG, "Credential Manager failed: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Google sign-in failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * @brief Signs out of Firebase and clears the saved credential selection.
     * @details Clearing the Credential Manager state means the next sign-in shows the account picker
     * again rather than silently reusing the last account.
     */
    suspend fun signOut() {
        auth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "clearCredentialState failed: ${e.message}", e)
        }
    }

    /**
     * @brief Suspends until this Play Services @c Task finishes, waiting on a background thread.
     * @details Firebase returns @c Task objects; @c Tasks.await blocks until completion and
     * @c withContext(Dispatchers.IO) keeps that wait off the caller's thread. Kept dependency-free
     * (no kotlinx-coroutines-play-services), mirroring @ref com.kempt.app.sync.FirestoreAccountabilityService.
     */
    private suspend fun <T> Task<T>.await(): T =
        withContext(Dispatchers.IO) { Tasks.await(this@await) }

    private companion object {
        /** @brief Log tag for sign-in diagnostics: `adb logcat -s AuthRepository`. */
        const val TAG = "AuthRepository"
    }
}
