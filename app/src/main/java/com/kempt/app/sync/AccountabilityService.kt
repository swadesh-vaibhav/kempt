/**
 * @file
 * @brief The device→backend accountability channel interface and its stub implementation.
 */
package com.kempt.app.sync

import android.util.Log
import com.kempt.app.data.BlockEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @brief One-way channel from the device to the accountability backend.
 *
 * @details Carries heartbeats, event reports (block broken / tamper), and device-token
 * registration for push. This interface is the seam the rest of the app talks to; the real
 * HTTP client is deliberately not wired yet — there is no backend to point at — so the
 * shipped implementation is @ref StubAccountabilityService, a logged no-op. Swapping in a
 * real client requires no other changes in the app.
 */
interface AccountabilityService {

    /**
     * @brief Tells the backend the monitor is still alive.
     * @return @c true if the ping landed.
     */
    suspend fun sendHeartbeat(): Boolean

    /**
     * @brief Reports an accountability event so the partner can be notified.
     * @param event The event to send.
     * @return @c true if the report was delivered.
     */
    suspend fun report(event: BlockEvent): Boolean

    /**
     * @brief Registers (or refreshes) the FCM token so the backend can push to this device.
     * @param token The Firebase Cloud Messaging registration token.
     * @return @c true if the token was accepted.
     */
    suspend fun registerToken(token: String): Boolean
}

/**
 * @brief No-op @ref AccountabilityService used until a backend exists.
 *
 * @details Every call is logged and reported as "delivered" so the local pipelines (worker,
 * monitor, messaging) run end to end without a server. Replace with a real client that POSTs
 * to a configurable base URL. Sketch of the real thing:
 * @code
 * class HttpAccountabilityService(private val baseUrl: String, private val auth: AuthTokenProvider) {
 *     override suspend fun sendHeartbeat() = post("/v1/heartbeat", emptyMap())
 *     // ...
 * }
 * @endcode
 *
 * @note @c @Inject constructor() lets Hilt build this class; @c @Singleton keeps one instance app-wide.
 */
@Singleton
class StubAccountabilityService @Inject constructor() : AccountabilityService {

    /**
     * @brief Logs the heartbeat and reports success.
     * @return Always @c true.
     */
    override suspend fun sendHeartbeat(): Boolean {
        Log.i(TAG, "heartbeat -> (stub: no backend configured)")
        return true
    }

    /**
     * @brief Logs the event and reports success.
     * @param event The event that would be sent.
     * @return Always @c true.
     */
    override suspend fun report(event: BlockEvent): Boolean {
        Log.i(TAG, "report ${event.type} pkg=${event.packageName} at=${event.at} -> (stub)")
        return true
    }

    /**
     * @brief Logs a truncated token and reports success.
     * @param token The FCM token that would be registered.
     * @return Always @c true.
     */
    override suspend fun registerToken(token: String): Boolean {
        Log.i(TAG, "registerToken ${token.take(12)}… -> (stub)")
        return true
    }

    /** @brief Log tag for the stub's output. */
    private companion object {
        const val TAG = "Accountability"
    }
}
