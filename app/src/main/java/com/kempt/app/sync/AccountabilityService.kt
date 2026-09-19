package com.kempt.app.sync

import android.util.Log
import com.kempt.app.data.BlockEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one-way channel from the device to the accountability backend: heartbeats,
 * event reports (block broken / tamper), and device-token registration for push.
 *
 * This is the seam the rest of the app talks to. The real HTTP implementation is
 * deliberately not wired yet — there is no backend to point at — so the shipped
 * implementation is [StubAccountabilityService], a logged no-op. Swap in a real
 * client (see the commented sketch below) once the backend exists; nothing else
 * in the app needs to change.
 */
interface AccountabilityService {

    /** Tell the backend the monitor is still alive. Returns true if the ping landed. */
    suspend fun sendHeartbeat(): Boolean

    /** Report an accountability event so the partner can be notified. */
    suspend fun report(event: BlockEvent): Boolean

    /** Register (or refresh) the FCM token so the backend can push to this device. */
    suspend fun registerToken(token: String): Boolean
}

/**
 * No-op implementation used until a backend exists. Every call is logged and reported
 * as "delivered" so the local pipelines (worker, monitor, messaging) run end to end
 * without a server. Replace with a real client that POSTs to a configurable base URL.
 *
 * Sketch of the real thing:
 * ```
 * class HttpAccountabilityService(private val baseUrl: String, private val auth: AuthTokenProvider) {
 *     override suspend fun sendHeartbeat() = post("/v1/heartbeat", emptyMap())
 *     ...
 * }
 * ```
 */
@Singleton
class StubAccountabilityService @Inject constructor() : AccountabilityService {

    override suspend fun sendHeartbeat(): Boolean {
        Log.i(TAG, "heartbeat -> (stub: no backend configured)")
        return true
    }

    override suspend fun report(event: BlockEvent): Boolean {
        Log.i(TAG, "report ${event.type} pkg=${event.packageName} at=${event.at} -> (stub)")
        return true
    }

    override suspend fun registerToken(token: String): Boolean {
        Log.i(TAG, "registerToken ${token.take(12)}… -> (stub)")
        return true
    }

    private companion object {
        const val TAG = "Accountability"
    }
}
