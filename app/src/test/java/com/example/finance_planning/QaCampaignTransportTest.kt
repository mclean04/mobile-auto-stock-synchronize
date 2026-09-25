package com.example.finance_planning

import com.example.finance_planning.core.*
import com.example.finance_planning.network.BackendApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class QaCampaignTransportTest {
    private lateinit var admissionFile: File
    private val event = "3e0f2b9b-62f9-49f7-954b-26a716a96e40"
    private val device = "d506628f-c0b8-4d6a-9c21-612128341ede"
    private val campaign = QaCampaign("campaign", "notifications", "a".repeat(64), "NOTIFICATION")
    private val config = QaNotificationConfig("qa-user", device, "qa-source-A0001:3",
        "local-test-only", campaign, mapOf(event to "N1"))
    @Before fun isolate() {
        admissionFile = File.createTempFile("qa-transport-admission-", ".json")
        admissionFile.writeText(JSONObject().put("schema_version", "finance-qa-startup-admission.v1")
            .put("mode", "QA_NOTIFICATION").put("firebase_project_id", "auto-stock-synchronization")
            .put("target_uid", config.targetUid).put("target_device_id", device)
            .put("notification_namespace", config.namespace).put("campaign", campaign.json()).toString())
        QaStartupIsolation.loadPrivateSelection(admissionFile)
    }
    @After fun cleanup() { admissionFile.delete(); QaStartupIsolation.loadPrivateSelection(admissionFile) }
    private fun status(state: String = "ACTIVE") = campaign.json()
        .put("contract_version", "finance-qa-campaign.v1").put("session_state", state)
        .put("server_now", "2026-09-25T13:00:00Z").put("max_session_seconds", 7200)
        .put("session_started_at", "2026-09-25T12:00:00Z").put("business_ends_at", "2026-09-25T13:30:00Z")
        .put("restoration_ends_at", "2026-09-25T13:45:00Z").put("session_ends_at", "2026-09-25T14:00:00Z")
    private fun binding(eventId: String = event) = campaign.json()
        .put("bindings", JSONObject().put("event_id", eventId))
        .put("evidence", JSONObject().put("artifact_readback", "b".repeat(64)))
        .put("admitted_at", "2026-09-25T12:15:00Z")
        .put("case_opens_at", "2026-09-25T12:25:00Z").put("case_ends_at", "2026-09-25T13:30:00Z")
    private fun response(value: JSONObject) = MockResponse().setBody(value.toString())
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 18766) }

    @Test fun openedReceiptUsesOriginalCaseHeadersAfterTrustedBindingRead() = runBlocking {
        server().use { server ->
            server.enqueue(response(status()))
            server.enqueue(response(binding()))
            server.enqueue(response(JSONObject().put("accepted", true)))
            BackendApi.qaNotifications(config).receipt(event, device, "OPENED")
            assertEquals("/qa/status", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            assertEquals("/qa/cases/N1", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            val receipt = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/v1/notifications/$event/receipts", receipt.path)
            assertEquals("N1", receipt.getHeader("X-QA-Case-ID"))
            assertEquals(campaign.campaignId, receipt.getHeader("X-QA-Campaign-ID"))
            assertEquals(campaign.sessionId, receipt.getHeader("X-QA-Session-ID"))
            assertEquals(campaign.manifestSha256, receipt.getHeader("X-QA-Manifest-SHA256"))
            val payload = JSONObject(receipt.body.readUtf8())
            assertEquals("OPENED", payload.getString("state"))
            assertEquals(device, payload.getString("device_id"))
        }
    }

    @Test fun readyExpiredAndDeadlineNeverSendReceipt() = runBlocking {
        server().use { server ->
            for (status in listOf(status("READY"), status("EXPIRED"), status().put("server_now", "2026-09-25T14:00:00Z"))) {
                server.enqueue(response(status))
                assertTrue(runCatching { BackendApi.qaNotifications(config).receipt(event, device, "OPENED") }.isFailure)
                assertEquals("/qa/status", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
                assertNull(server.takeRequest(20, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test fun wrongEventBindingNeverSendsReceipt() = runBlocking {
        server().use { server ->
            server.enqueue(response(status()))
            server.enqueue(response(binding("89b9e3dc-6408-40fa-9bb9-e30c734d47e1")))
            assertTrue(runCatching { BackendApi.qaNotifications(config).receipt(event, device, "RECEIVED") }.isFailure)
            assertEquals("/qa/status", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            assertEquals("/qa/cases/N1", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            assertNull(server.takeRequest(20, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun missingAdmissionAndWrongTargetNeverReachEvenTheControlSocket() = runBlocking {
        server().use { server ->
            assertTrue(runCatching { BackendApi.qaNotifications(config)
                .registerDevice("89b9e3dc-6408-40fa-9bb9-e30c734d47e1", "unit-only") }.isFailure)
            assertNull(server.takeRequest(20, TimeUnit.MILLISECONDS))
            admissionFile.writeText("{malformed")
            QaStartupIsolation.loadPrivateSelection(admissionFile)
            assertTrue(runCatching { BackendApi.qaNotifications(config).notifications() }.isFailure)
            assertNull(server.takeRequest(20, TimeUnit.MILLISECONDS))
        }
    }
}
