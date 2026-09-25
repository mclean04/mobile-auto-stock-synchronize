package com.example.finance_planning.debug

import android.app.Activity
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import com.example.finance_planning.PlanningApp
import org.json.JSONObject
import java.util.UUID

/** Debug-only, shell/DUMP-protected metadata readback. It never writes tokens, email, or credentials. */
class QaMetadataActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val app = application as PlanningApp
        val repo = app.repository
        val uid = repo.identity.uid()
        val device = repo.existingDevice()
        val metadata = JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("notification_permission", NotificationManagerCompat.from(app).areNotificationsEnabled())
            .put("firebase_configured", repo.identity.configured)
            .put("firebase_user_present", uid != null)
            .put("approved", repo.approved())
            .put("qa_notification_configured", repo.qaNotificationsConfigured())
            .put("qa_notification_isolation_enabled", repo.qaNotificationIsolationEnabled())
            .put("fcm_token_included", false)
        if (uid != null) metadata.put("target_uid", uid)
        if (device != null) {
            UUID.fromString(device)
            metadata.put("target_device_id", device)
        }
        openFileOutput(FILE_NAME, MODE_PRIVATE).bufferedWriter().use { it.write(metadata.toString()) }
        finish()
    }

    companion object { const val FILE_NAME = "qa-metadata.json" }
}
