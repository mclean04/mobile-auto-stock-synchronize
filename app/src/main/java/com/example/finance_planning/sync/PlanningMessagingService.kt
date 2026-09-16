package com.example.finance_planning.sync

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
import com.example.finance_planning.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.UUID
import java.time.Instant

class PlanningMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { SyncSchedule.refresh(this) }

    override fun onMessageReceived(message: RemoteMessage) {
        val repo = (application as PlanningApp).repository
        if (!repo.approved()) return
        val uid = repo.identity.uid() ?: return
        if (message.data["target_uid"] != uid || message.data["schema_version"] != "1") return
        val event = message.data["event_id"] ?: return
        if (runCatching { UUID.fromString(event) }.isFailure) return
        SyncSchedule.receipt(this, event, "RECEIVED", uid)
    }

    companion object {
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("planning", "Thông báo planning", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { setShowBadge(true) })
        }
        fun show(context: Context, event: JSONObject) {
            val isTest = event.optJSONObject("sheet")?.optString("mode") == "TEST" &&
                event.optBoolean("is_current") && runCatching {
                    Instant.parse(event.optString("test_push_until")).isAfter(Instant.now())
                }.getOrDefault(false)
            if (!event.optBoolean("requires_review") && !isTest) return
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
            val id = event.getString("event_id")
            val manager = context.getSystemService(NotificationManager::class.java)
            createChannel(context)
            val intent = Intent(context, MainActivity::class.java).putExtra("event_id", id)
                .putExtra("target_uid", (context.applicationContext as PlanningApp).repository.identity.uid())
                .setAction("planning:" + id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(id, 1, NotificationCompat.Builder(context, "planning")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(if (isTest) "[TEST] Thông báo planning" else "Bạn có thông báo planning")
                .setContentText("Mở app để xem thông báo dành cho tài khoản của bạn.")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setNumber(1).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL).setOnlyAlertOnce(true)
                .setContentIntent(pending).setAutoCancel(true).build())
        }
    }
}
