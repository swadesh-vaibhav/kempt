/**
 * @file
 * @brief Firestore-backed @ref AccountabilityService — the real device→backend channel.
 */
package com.kempt.app.sync

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.kempt.app.data.BlockEvent
import com.kempt.app.data.LockStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @brief Real @ref AccountabilityService that writes accountability data to Cloud Firestore.
 *
 * @details Bound in place of @ref StubAccountabilityService (see the @c @Binds in
 * @ref com.kempt.app.di.AppModule). Each write is scoped to the signed-in user's uid and this
 * device's id, matching the layout the security rules enforce:
 *  - @c report            → creates a doc under @c users/{uid}/events
 *  - @c sendHeartbeat     → upserts @c users/{uid}/devices/{deviceId} (armed + heartbeat time)
 *  - @c registerToken     → upserts @c users/{uid}/devices/{deviceId} (fcmToken)
 *
 * @warning The rules require @c request.auth, so every method is a no-op returning @c false until
 * a user is signed in with Firebase Auth (sign-in is not wired yet). No crash — the calls simply
 * report failure and the local Room log keeps the events until sign-in exists, at which point the
 * @ref HeartbeatWorker flush will deliver them. Wiring Auth is the only thing left to make this live.
 *
 * @note @c @Inject @c constructor lets Hilt build this; @c @Singleton keeps one instance app-wide.
 * All four parameters are Hilt-provided singletons.
 *
 * @param context Application context, used only to read a stable device id.
 * @param firestore The Firestore client.
 * @param auth The Firebase Auth client, source of the current user's uid.
 * @param lockState The lock-state store, read to report the device's @c armed flag.
 */
@Singleton
class FirestoreAccountabilityService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val lockState: LockStateStore,
) : AccountabilityService {

    /**
     * @brief A stable-per-install identifier for this device, used as the @c devices document id.
     * @details @c Settings.Secure.ANDROID_ID is unique per app-signing-key per device and needs no
     * permission. @c by @c lazy is a Kotlin delegate that runs the block once on first access and
     * caches the result, so all heartbeats/tokens for this phone land on the same document.
     */
    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
    }

    /**
     * @brief Reports an accountability event as a new @c users/{uid}/events document.
     * @param event The on-device event to mirror to the backend.
     * @return @c true if the write landed; @c false if not signed in or the write failed/timed out.
     */
    override suspend fun report(event: BlockEvent): Boolean {
        val uid = auth.currentUser?.uid ?: return notSignedIn("report")
        return runCatchingWrite("report ${event.type}") {
            val doc = hashMapOf<String, Any>(
                Fs.TYPE to event.type,
                Fs.AT to Timestamp(Date(event.at)),   // epoch millis → Firestore timestamp
                Fs.DEVICE_ID to deviceId,
            )
            // packageName is optional in the rules; only include it when present. Writing an
            // explicit null would add the key and be rejected by the strict field validation.
            event.packageName?.let { doc[Fs.PACKAGE_NAME] = it }

            firestore.collection(Fs.USERS).document(uid)
                .collection(Fs.EVENTS)
                .add(doc)                 // Firestore assigns the document id
                .awaitCompat()
        }
    }

    /**
     * @brief Sends a heartbeat by upserting this device's doc with the current armed flag + time.
     * @return @c true if the write landed; @c false if not signed in or the write failed/timed out.
     */
    override suspend fun sendHeartbeat(): Boolean {
        val uid = auth.currentUser?.uid ?: return notSignedIn("heartbeat")
        return upsertDevice(
            uid,
            mapOf(
                Fs.ARMED to lockState.isArmedNow(),
                Fs.LAST_HEARTBEAT_AT to FieldValue.serverTimestamp(),
            )
        )
    }

    /**
     * @brief Registers/refreshes the FCM push token on this device's doc.
     * @param token The Firebase Cloud Messaging registration token.
     * @return @c true if the write landed; @c false if not signed in or the write failed/timed out.
     */
    override suspend fun registerToken(token: String): Boolean {
        val uid = auth.currentUser?.uid ?: return notSignedIn("registerToken")
        return upsertDevice(uid, mapOf(Fs.FCM_TOKEN to token))
    }

    /**
     * @brief Creates the device doc if missing, otherwise updates the given mutable fields.
     * @details Runs inside a Firestore *transaction* — an atomic read-then-write. The rules make
     * @c createdAt and @c platform immutable, so no single write serves both cases: a brand-new
     * doc must include them, while an existing doc must not resend them (a changed @c createdAt is
     * rejected). The transaction reads once to pick the correct path.
     * @param uid The signed-in user's id.
     * @param mutable The fields to set (heartbeat: armed + time; token: fcmToken).
     * @return @c true on success, @c false on failure.
     */
    private suspend fun upsertDevice(uid: String, mutable: Map<String, Any>): Boolean {
        val armed = lockState.isArmedNow()   // read here — the transaction body cannot suspend
        val ref = deviceRef(uid)
        return runCatchingWrite("device upsert") {
            firestore.runTransaction<Void?> { txn ->
                if (txn.get(ref).exists()) {
                    txn.update(ref, mutable)
                } else {
                    val created = hashMapOf<String, Any>(
                        Fs.PLATFORM to Fs.PLATFORM_ANDROID,
                        Fs.ARMED to armed,
                        Fs.CREATED_AT to FieldValue.serverTimestamp(),
                    )
                    created.putAll(mutable)   // adds lastHeartbeatAt / fcmToken (re-sets armed harmlessly)
                    txn.set(ref, created)
                }
                null   // a transaction must return a value; we need none
            }.awaitCompat()
        }
    }

    /** @brief @return A reference to @c users/{uid}/devices/{deviceId}. */
    private fun deviceRef(uid: String): DocumentReference =
        firestore.collection(Fs.USERS).document(uid)
            .collection(Fs.DEVICES).document(deviceId)

    /**
     * @brief Runs a Firestore write, logging and swallowing any failure into @c false.
     * @param label A short description used in the log line.
     * @param block The suspending write to attempt.
     * @return @c true if @p block completed, @c false if it threw (including a timeout offline).
     */
    private suspend fun runCatchingWrite(label: String, block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: Exception) {
            Log.w(TAG, "$label failed: ${e.message}", e)
            false
        }

    /** @brief Logs a skipped call (no authenticated user) and returns @c false. */
    private fun notSignedIn(label: String): Boolean {
        Log.i(TAG, "$label skipped — no signed-in user yet")
        return false
    }

    /**
     * @brief Suspends until this Play Services @c Task finishes (with a timeout), off the main thread.
     * @details Firebase returns results as @c Task objects — callback-style futures.
     * @c Tasks.await blocks the calling thread until the task completes; @c withContext(Dispatchers.IO)
     * moves that wait onto a background thread so no coroutine blocks the UI. The timeout matters
     * because, while offline, a write's task stays pending until connectivity returns — the timeout
     * turns that into a quick failure so the caller can retry later instead of hanging.
     *
     * Kept dependency-free on purpose: the kotlinx @c Task.await() extension would add
     * kotlinx-coroutines-play-services, whose version must be kept in lockstep with the coroutines core.
     * @return The task's result once it completes.
     */
    private suspend fun <T> Task<T>.awaitCompat(): T =
        withContext(Dispatchers.IO) {
            Tasks.await(this@awaitCompat, WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    private companion object {
        /** @brief Log tag; matches the stub so `adb logcat -s Accountability` shows both. */
        const val TAG = "Accountability"

        /** @brief How long to wait for a write's server ack before giving up (and retrying later). */
        const val WRITE_TIMEOUT_SECONDS = 15L
    }
}
