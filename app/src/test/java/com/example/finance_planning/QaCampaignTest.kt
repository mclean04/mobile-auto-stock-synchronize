package com.example.finance_planning

import com.example.finance_planning.core.QaCampaign
import com.example.finance_planning.core.QaCampaignOperation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class QaCampaignTest {
    private val campaign = QaCampaign("campaign-1", "safety-1", "a".repeat(64), "SAFETY_RECOVERY")
    private fun status(now: String = "2026-09-25T12:01:00Z") = JSONObject()
        .put("contract_version", "finance-qa-campaign.v1").put("campaign_id", "campaign-1")
        .put("session_id", "safety-1").put("session_kind", "SAFETY_RECOVERY")
        .put("manifest_sha256", "a".repeat(64)).put("server_now", now)
        .put("session_state", "ACTIVE").put("max_session_seconds", 7200)
        .put("session_started_at", "2026-09-25T12:00:00Z")
        .put("business_ends_at", "2026-09-25T13:30:00Z")
        .put("restoration_ends_at", "2026-09-25T13:45:00Z")
        .put("session_ends_at", "2026-09-25T14:00:00Z")
        .put("preparation_started_at", "2026-09-25T11:00:00Z")
        .put("preparation_expires_at", "2026-09-25T12:00:00Z")

    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun deadlinesAreExclusiveAndCompletionDoesNotAdmitBusiness() {
        campaign.validate(status("2026-09-25T13:29:59Z"), QaCampaignOperation.BUSINESS)
        rejected { campaign.validate(status("2026-09-25T13:30:00Z"), QaCampaignOperation.BUSINESS) }
        campaign.validate(status("2026-09-25T13:30:00Z"), QaCampaignOperation.COMPLETION)
        rejected { campaign.validate(status("2026-09-25T13:45:00Z"), QaCampaignOperation.RESTORE) }
        rejected { campaign.validate(status("2026-09-25T14:00:00Z"), QaCampaignOperation.COMPLETION) }
    }

    @Test fun missingOrMismatchedIdentityDeadlineOrClockFailsClosed() {
        for (key in listOf("campaign_id", "session_id", "manifest_sha256", "session_kind",
            "business_ends_at", "restoration_ends_at", "session_ends_at", "server_now")) {
            rejected { campaign.validate(status().apply { remove(key) }, QaCampaignOperation.BUSINESS) }
            rejected { campaign.validate(status().put(key, "incorrect"), QaCampaignOperation.BUSINESS) }
        }
        rejected { campaign.validate(status().put("business_ends_at", "2026-09-25T13:31:00Z"), QaCampaignOperation.BUSINESS) }
        rejected { campaign.validate(status("2026-09-25T12:01:00"), QaCampaignOperation.BUSINESS) }
        rejected { campaign.validate(status(), QaCampaignOperation.BUSINESS, Instant.parse("2026-09-25T12:00:01Z")) }
    }

    @Test fun readyAndTerminalStatesNeverPermitExecutionOrReceipts() {
        for (state in listOf("PREPARING", "READY", "CLOSED", "EXPIRED")) {
            for (op in listOf(QaCampaignOperation.BUSINESS, QaCampaignOperation.COMPLETION))
                rejected { campaign.validate(status().put("session_state", state), op) }
            campaign.validate(status().put("session_state", state), QaCampaignOperation.READ)
        }
        campaign.validate(status("2026-09-25T11:15:00Z").put("session_state", "PREPARING"), QaCampaignOperation.PREPARE)
        rejected { campaign.validate(status("2026-09-25T12:00:00Z").put("session_state", "PREPARING"), QaCampaignOperation.PREPARE) }
    }

    @Test fun headersBindOriginalCaseAndRejectForeignCaseOrInjection() {
        assertEquals("X5", campaign.headers("X5")["X-QA-Case-ID"])
        assertEquals("a".repeat(64), campaign.headers()["X-QA-Manifest-SHA256"])
        rejected { campaign.headers("N1") }
        rejected { QaCampaign("c\r\nX: 1", "s", "a".repeat(64), "PLANNING") }
    }

    @Test fun eventBindingRequiresSameCampaignSessionHashAndObservedEvent() {
        val c = QaCampaign("campaign", "notification-session", "a".repeat(64), "NOTIFICATION")
        val record = c.json().put("evidence", JSONObject().put("artifact_readback", "b".repeat(64)))
            .put("bindings", JSONObject().put("event_id", "observed-event"))
            .put("admitted_at", "2026-09-25T12:15:00Z")
            .put("case_opens_at", "2026-09-25T12:25:00Z")
            .put("case_ends_at", "2026-09-25T13:30:00Z")
        c.validateNotificationBinding("N1", "observed-event", record)
        rejected { c.validateNotificationBinding("N3", "observed-event", record) }
        rejected { c.validateNotificationBinding("N1", "another-event", record) }
        rejected { c.validateNotificationBinding("N1", "observed-event", record.put("session_id", "other")) }
    }
}
