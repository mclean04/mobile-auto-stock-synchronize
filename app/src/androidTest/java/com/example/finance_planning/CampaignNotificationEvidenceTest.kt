package com.example.finance_planning

import androidx.test.platform.app.InstrumentationRegistry
import com.example.finance_planning.core.ProductionObservationLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Reads actual persisted FCM evidence; never fabricates delivery, opens a notification or registers FCM. */
class CampaignNotificationEvidenceTest {
    @Test fun captureActualReceiptEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as PlanningApp
        val args = InstrumentationRegistry.getArguments()
        val eventId = requireNotNull(args.getString("event_id")).also(UUID::fromString)
        val deviceId = requireNotNull(args.getString("expected_device_id"))
        val uid = requireNotNull(args.getString("expected_uid"))
        val since = Instant.parse(requireNotNull(args.getString("session_start_utc")))
        val repo = app.repository
        assertEquals(uid, repo.identity.uid())
        assertEquals(deviceId, repo.existingDevice())
        assertTrue(repo.qaNotificationsConfigured())
        assertTrue(repo.qaNotificationIsolationEnabled())
        val delivery = repo.notificationDelivery(eventId)
        val campaign = requireNotNull(delivery.campaign)
        assertEquals(args.getString("campaign_id"), campaign.campaignId)
        assertEquals(args.getString("session_id"), campaign.sessionId)
        assertEquals(args.getString("manifest_sha256"), campaign.manifestSha256)
        assertEquals(args.getString("case_id"), delivery.caseId)
        val directory = File(app.noBackupFilesDir, ProductionObservationLog.DIRECTORY_NAME)
        val records = (0..3).map { File(directory, "observation-$it.jsonl") }
            .filter(File::isFile).flatMap { it.readLines() }.filter(String::isNotBlank)
            .map(::JSONObject).filter { it.optString("event_id") == eventId &&
                Instant.parse(it.getString("timestamp_utc")) >= since }
        fun count(stage: String, result: String? = null) = records.count {
            it.getString("stage") == stage && (result == null || it.getString("result") == result)
        }
        val output = JSONObject().put("schema_version", "android-notification-evidence.v1")
            .put("event_id", eventId).put("device_id", deviceId)
            .put("campaign_id", campaign.campaignId).put("session_id", campaign.sessionId)
            .put("manifest_sha256", campaign.manifestSha256).put("case_id", delivery.caseId)
            .put("captured_at_utc", Instant.now().toString())
            .put("received_count", count("RECEIVED"))
            .put("displayed_count", count("DISPLAYED", "OBSERVED"))
            .put("opened_count", count("OPENED", "OBSERVED"))
            .put("opened_locally", repo.notificationOpened(eventId))
            .put("opened_receipt_accepted", records.any {
                it.getString("action") == "NOTIFICATION_OPENED_RECEIPT" &&
                    it.getString("stage") == "SERVER_ACCEPTED" && it.getString("result") == "ACCEPTED"
            })
            .put("qa_isolation", true).put("registration_performed", false)
            .put("receipt_synthesized", false)
        File(app.filesDir, "campaign-notification-evidence.json").writeText(output.toString())
    }
}
