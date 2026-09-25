package com.example.finance_planning.sync

import com.example.finance_planning.R
import com.example.finance_planning.core.AppText
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.finance_planning.MainActivity
import com.example.finance_planning.PlanningApp
import com.example.finance_planning.core.NotificationContent
import com.example.finance_planning.core.ObservationAction
import com.example.finance_planning.core.ObservationComponent
import com.example.finance_planning.core.ObservationCorrelation
import com.example.finance_planning.core.ObservationResult
import com.example.finance_planning.core.ObservationStage
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.UUID

class PlanningMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { SyncSchedule.refresh(this) }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as PlanningApp
        val repo = app.repository
        if (!repo.approved()) return
        val uid = repo.identity.uid() ?: return
        if (message.data["target_uid"]?.let { it != uid } == true) return
        if (message.data["event_id"] == null && message.notification != null) {
            if (repo.qaNotificationIsolationEnabled()) return
            val payload = message.notification!!
            val event = SyncSchedule.console(this, uid, message.messageId ?: UUID.randomUUID().toString(),
                payload.title ?: AppText.get(R.string.new_notification), payload.body ?: "")
            app.observationLog.record(ObservationComponent.NOTIFICATION, ObservationAction.FCM_RECEIVE,
                ObservationStage.RECEIVED, ObservationResult.ACCEPTED,
                ObservationCorrelation.event(event))
            // Present immediately; the durable worker saves content and refreshes the inbox.
            show(this, event)
            return
        }
        val delivery = repo.notificationPush(message.data) ?: return
        app.observationLog.record(ObservationComponent.NOTIFICATION, ObservationAction.FCM_RECEIVE,
            ObservationStage.RECEIVED, ObservationResult.ACCEPTED,
            ObservationCorrelation.notification(delivery))
        SyncSchedule.receipt(this, delivery, "RECEIVED")
    }

    companion object {
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("planning", AppText.get(R.string.planning_notifications), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { setShowBadge(true) })
        }
        fun show(context: Context, event: JSONObject): Boolean {
            if (!NotificationContent.shouldDisplay(event)) return false
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return false
            val id = event.getString("event_id")
            val manager = context.getSystemService(NotificationManager::class.java)
            createChannel(context)
            val intent = Intent(context, MainActivity::class.java).putExtra("event_id", id)
                .putExtra("notification_inbox", true)
                .putExtra("target_uid", (context.applicationContext as PlanningApp).repository.identity.uid())
                .setAction("planning:" + id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(id, 1, NotificationCompat.Builder(context, "planning")
                .setSmallIcon(R.drawable.ic_stat_planning)
                .setContentTitle(NotificationContent.title(event))
                .setContentText(NotificationContent.body(event))
                .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationContent.body(event)))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setNumber(1).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL).setOnlyAlertOnce(true)
                .setContentIntent(pending).setAutoCancel(true).build())
            return true
        }
    }
}
