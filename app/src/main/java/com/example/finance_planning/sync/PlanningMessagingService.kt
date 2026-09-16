package com.example.finance_planning.sync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// Upload-only Android accounts are never subscribed to the owner's shared planning.
class PlanningMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Unit
    override fun onMessageReceived(message: RemoteMessage) = Unit
}
