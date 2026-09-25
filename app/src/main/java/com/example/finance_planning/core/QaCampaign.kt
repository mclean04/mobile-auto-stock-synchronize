package com.example.finance_planning.core

import org.json.JSONObject
import java.time.Instant

/** Debug/test control-plane identity. Never accepted from FCM or a product screen. */
data class QaCampaign(val campaignId: String, val sessionId: String, val manifestSha256: String,
                      val sessionKind: String) {
    init {
        require(listOf(campaignId, sessionId).all { Regex("[A-Za-z0-9._:-]{1,200}").matches(it) })
        require(Regex("[0-9a-f]{64}").matches(manifestSha256))
        require(sessionKind in setOf("PLANNING", "NOTIFICATION", "SAFETY_RECOVERY"))
    }

    fun headers(caseId: String? = null): Map<String, String> {
        if (caseId != null) require(caseId in cases())
        return buildMap {
            put("X-QA-Campaign-ID", campaignId)
            put("X-QA-Session-ID", sessionId)
            put("X-QA-Manifest-SHA256", manifestSha256)
            if (caseId != null) put("X-QA-Case-ID", caseId)
        }
    }

    private fun cases() = when (sessionKind) {
        "PLANNING" -> (1..6).map { "P$it" }
        "NOTIFICATION" -> (1..4).map { "N$it" }
        else -> (1..6).map { "X$it" }
    }

    fun json(): JSONObject = JSONObject().put("campaign_id", campaignId).put("session_id", sessionId)
        .put("manifest_sha256", manifestSha256).put("session_kind", sessionKind)

    fun validateNotificationBinding(caseId: String, eventId: String, readback: JSONObject) {
        require(sessionKind == "NOTIFICATION" && caseId in setOf("N1", "N2"))
        require(readback.getString("campaign_id") == campaignId)
        require(readback.getString("session_id") == sessionId)
        require(readback.getString("manifest_sha256") == manifestSha256)
        require(readback.getJSONObject("bindings").getString("event_id") == eventId)
        require(readback.getJSONObject("evidence").length() > 0)
        utc(readback.getString("admitted_at"))
        require(utc(readback.getString("case_opens_at")) < utc(readback.getString("case_ends_at")))
    }

    fun validate(status: JSONObject, operation: QaCampaignOperation,
                 requestedStart: Instant? = null, requestedEnd: Instant? = null): Instant {
        require(status.getString("contract_version") == "finance-qa-campaign.v1")
        require(status.getString("campaign_id") == campaignId)
        require(status.getString("session_id") == sessionId)
        require(status.getString("manifest_sha256") == manifestSha256)
        require(status.getString("session_kind") == sessionKind)
        val now = utc(status.getString("server_now"))
        val state = status.getString("session_state")
        require(state in setOf("PREPARING", "READY", "ACTIVE", "CLOSED", "EXPIRED"))
        if (operation == QaCampaignOperation.READ) return now
        if (operation == QaCampaignOperation.PREPARE) {
            require(state == "PREPARING")
            val start = utc(status.getString("preparation_started_at"))
            val end = utc(status.getString("preparation_expires_at"))
            require(end > start && end <= start.plusSeconds(3600) && now >= start && now < end)
            return end
        }
        require(state == "ACTIVE")
        require(status.getInt("max_session_seconds") == 7200)
        val start = utc(status.getString("session_started_at"))
        val end = utc(status.getString("session_ends_at"))
        val business = utc(status.getString("business_ends_at"))
        val restoration = utc(status.getString("restoration_ends_at"))
        require(end == start.plusSeconds(7200))
        require(business == start.plusSeconds(5400) && restoration == start.plusSeconds(6300))
        require(requestedStart == null || requestedStart == start)
        require(requestedEnd == null || requestedEnd == end)
        val deadline = when (operation) {
            QaCampaignOperation.BUSINESS -> business
            QaCampaignOperation.RESTORE -> restoration
            else -> end
        }
        require(now >= start && now < deadline)
        return deadline
    }

    companion object {
        fun parse(value: JSONObject) = QaCampaign(value.getString("campaign_id"),
            value.getString("session_id"), value.getString("manifest_sha256"), value.getString("session_kind"))
        private fun utc(value: String): Instant {
            // Instant rejects local timestamps. Contract requires UTC, not a business-clock offset.
            require(value.endsWith("Z") || value.endsWith("+00:00"))
            return Instant.parse(value)
        }
    }
}

enum class QaCampaignOperation { READ, PREPARE, BUSINESS, RESTORE, COMPLETION }
