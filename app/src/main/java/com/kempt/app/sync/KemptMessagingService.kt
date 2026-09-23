/**
 * @file
 * @brief Firebase Cloud Messaging service that receives partner unlock decisions and alerts.
 */
package com.kempt.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kempt.app.data.LockStateStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @brief Receives pushes from the backend: partner unlock decisions and alerts.
 *
 * @details Requires a Firebase project, @c google-services.json, and the google-services
 * plugin to actually deliver at runtime; without them this compiles but never fires.
 * Recognised @c data payloads:
 *  - @c type=unlock_approval — the partner approved an unlock; end the active lock.
 *  - @c type=partner_alert — informational; surfaced as a notification.
 *
 * @note @c @AndroidEntryPoint enables Hilt field injection into this framework-owned service.
 */
@AndroidEntryPoint
class KemptMessagingService : FirebaseMessagingService() {

    /** @brief Outbound channel, used here to register new FCM tokens. Injected by Hilt. */
    @Inject lateinit var accountability: AccountabilityService

    /** @brief Lock-state store, disarmed when an unlock approval arrives. Injected by Hilt. */
    @Inject lateinit var lockState: LockStateStore

    /** @brief Coroutine scope for handling pushes off the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * @brief Called by FCM when the device token changes; forwards it to the backend.
     * @param token The new FCM registration token.
     */
    override fun onNewToken(token: String) {
        scope.launch { accountability.registerToken(token) }
    }

    /**
     * @brief Dispatches an incoming push by its @c type data field.
     * @details An unlock approval disarms the lock, cancels the heartbeat, and notifies the
     * user; anything else that carries a body is surfaced as a plain notification.
     * @param message The received message.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            TYPE_UNLOCK_APPROVAL -> scope.launch {
                lockState.disarm()
                HeartbeatWorker.cancel(applicationContext)
                notify(
                    data["title"] ?: "Unlocked",
                    data["body"] ?: "Your partner approved the unlock."
                )
            }

            else -> {
                val title = data["title"] ?: message.notification?.title ?: "Kempt"
                val body = data["body"] ?: message.notification?.body.orEmpty()
                if (body.isNotEmpty()) notify(title, body)
            }
        }
    }

    /**
     * @brief Posts a high-importance notification, if allowed.
     * @details Creates the alert channel and drops the notification silently when
     * @c POST_NOTIFICATIONS isn't granted (Android 13+).
     * @param title Notification title.
     * @param body Notification body.
     */
    private fun notify(title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kempt alerts", NotificationManager.IMPORTANCE_HIGH)
        )

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return // POST_NOTIFICATIONS not granted (API 33+); drop silently

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, notification)
    }

    /** @brief Cancels the coroutine scope so in-flight handlers don't outlive the service. */
    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    /** @brief Push payload keys/values and notification identifiers. */
    private companion object {
        /** @brief @c data.type value for a partner-approved unlock. */
        const val TYPE_UNLOCK_APPROVAL = "unlock_approval"

        /** @brief Notification channel id for alerts. */
        const val CHANNEL_ID = "kempt_alerts"

        /** @brief Notification id for alert notifications. */
        const val ALERT_NOTIFICATION_ID = 2001
    }
}
