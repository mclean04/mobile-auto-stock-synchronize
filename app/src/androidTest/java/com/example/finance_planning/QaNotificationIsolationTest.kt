package com.example.finance_planning

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.finance_planning.auth.MobileIdentity
import com.example.finance_planning.core.Vault
import com.example.finance_planning.data.LocalDb
import com.example.finance_planning.data.PlanningRepository
import com.example.finance_planning.network.BackendApi
import com.example.finance_planning.network.Transport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QaNotificationIsolationTest {
    @Test fun encryptedConfigPinsUidDeviceNamespaceAndRejectsProductionFallback() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val preferences = "qa-notification-$suffix-private_settings"
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("qa-notification-$suffix-$name", mode)
        }
        var uid: String? = "qa-user"
        val identity = object : MobileIdentity(isolated) { override fun uid() = uid }
        val dbName = "qa-notification-$suffix.db"
        val db = Room.databaseBuilder(base, LocalDb::class.java, dbName).build()
        try {
            val repo = PlanningRepository(identity, Vault(isolated), db,
                BackendApi(object : Transport() {
                    override suspend fun request(url: String, method: String,
                                                 headers: Map<String, String>, body: JSONObject?): String =
                        error("No network is allowed in this test")
                }, { emptyMap() }))
            val device = repo.device()
            UUID.fromString(device)
            repo.installQaNotificationConfig(JSONObject().put("target_uid", uid)
                .put("target_device_id", device).put("notification_namespace", "qa-source-A0001:3")
                .put("bearer", "private-test-bearer"))
            assertTrue(repo.qaNotificationsConfigured())
            val qa = mapOf("schema_version" to "1", "event_id" to UUID.randomUUID().toString(),
                "plan_id" to "test-dnse-daily-20260922-1030", "version" to "1",
                "type" to "PLANNING_REVIEW_REQUIRED", "target_uid" to "qa-user",
                "test_marker" to "DEMO_ONLY", "notification_namespace" to "qa-source-A0001:3")
            assertNotNull(repo.notificationPush(qa))
            assertNull(repo.notificationPush(qa - "test_marker" - "notification_namespace"))
            uid = "other-user"
            assertFalse(repo.qaNotificationsConfigured())
            assertTrue(repo.qaNotificationIsolationEnabled())
            assertNull(repo.notificationPush(qa + ("target_uid" to "other-user")))
        } finally {
            db.close()
            base.deleteDatabase(dbName)
            base.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
