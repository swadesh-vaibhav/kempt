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
 * Receives pushes from the backend: partner unlock decisions and alerts. Requires a
 * Firebase project + `google-services.json` + the google-services plugin to actually
 * deliver at runtime; without them this compiles but never fires.
 *
 * Recognised `data` payloads:
 *  - `type=unlock_approval` — the partner approved an unlock; end the active lock.
 *  - `type=partner_alert`   — informational; surfaced as a notification.
 */
@AndroidEntryPoint
class KemptMessagingService : FirebaseMessagingService() {

    @Inject lateinit var accountability: AccountabilityService
    @Inject lateinit var lockState: LockStateStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNewToken(token: String) {
        scope.launch { accountability.registerToken(token) }
    }

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

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TYPE_UNLOCK_APPROVAL = "unlock_approval"
        const val CHANNEL_ID = "kempt_alerts"
        const val ALERT_NOTIFICATION_ID = 2001
    }
}
