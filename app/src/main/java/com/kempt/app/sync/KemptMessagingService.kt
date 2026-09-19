package com.kempt.app.sync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives pushes from the backend: partner unlock decisions and alerts.
 */
class KemptMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // TODO: register this device token with the backend.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // TODO: handle unlock-approval / partner-alert payloads.
    }
}
