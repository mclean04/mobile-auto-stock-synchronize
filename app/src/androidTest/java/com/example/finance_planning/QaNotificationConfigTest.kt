package com.example.finance_planning

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit operator entry points. Tests never print the bearer or FCM token. */
@RunWith(AndroidJUnit4::class)
class QaNotificationConfigTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as PlanningApp

    @Test fun installPrivateConfig() {
        val file = File(instrumentation.targetContext.filesDir, "qa-notification-config.json")
        try {
            require(file.isFile)
            app.repository.installQaNotificationConfig(JSONObject(file.readText()))
            assertTrue(app.repository.qaNotificationsConfigured())
        } finally {
            if (file.exists()) {
                file.writeText("")
                assertTrue(file.delete())
            }
        }
    }

    @Test fun registerConfiguredTarget() = runBlocking {
        assertTrue(app.repository.qaNotificationsConfigured())
        app.repository.registerPush()
        assertTrue(app.repository.pushRegistered())
    }

    @Test fun clearPrivateConfig() {
        app.repository.clearQaNotificationConfig()
        assertFalse(app.repository.qaNotificationsConfigured())
    }

    @Test fun verifiedTargetMetadata() {
        val repo = app.repository
        val allowed = androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()
        val configured = repo.identity.configured
        val uid = repo.identity.uid()
        val device = repo.device()
        UUID.fromString(device)
        val metadata = JSONObject().put("model", android.os.Build.MODEL)
            .put("notification_permission", allowed)
            .put("firebase_configured", configured)
            .put("firebase_user_present", uid != null)
            .put("approved", repo.approved())
            .put("target_device_id", device)
            .put("fcm_token_included", false)
        if (uid != null) metadata.put("target_uid", uid)
        println("QA_NOTIFICATION_METADATA=$metadata")
        assertTrue(android.os.Build.MODEL == "SM-X730")
        assertTrue("QA Firebase configuration is missing from this build", configured)
        assertNotNull("QA Firebase user is not signed in", uid)
        assertTrue(repo.approved())
    }
}
