package com.example.finance_planning.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.finance_planning.MainActivity
import com.example.finance_planning.PlanningApp
import com.example.finance_planning.R
import com.example.finance_planning.core.Vault
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

class PlanningMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Firebase keeps its token; never log it or persist plaintext ourselves.
        SyncSchedule.refresh(this)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (!(application as PlanningApp).repository.approved()) return
        val event = message.data["event_id"] ?: return
        if (runCatching { UUID.fromString(event) }.isFailure) return
        if (message.data["schema_version"] != "1") return
        SyncSchedule.receipt(this, event, "RECEIVED")
        val version = message.data["version"]?.toLongOrNull() ?: return
        val plan = message.data["plan_id"] ?: return
        val uid = (application as PlanningApp).repository.identity.uid() ?: return
        val vault = Vault(this)
        val dedupKey = "push:$uid:$plan"
        val previous = vault.get(dedupKey)?.toLongOrNull() ?: -1
        if (version <= previous) return
        // Push is only a hint. Opening always reloads the canonical event.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("planning", "Cập nhật planning", NotificationManager.IMPORTANCE_DEFAULT))
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, MainActivity::class.java).putExtra("event_id", event)
            .setAction("planning:" + event).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, event.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "planning")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Planning có cập nhật")
            .setContentText("Mở ứng dụng để kiểm tra kế hoạch mới nhất.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(plan, 1, notification)
        vault.put(dedupKey, version.toString())
    }
}
