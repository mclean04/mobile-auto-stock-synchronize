package com.example.finance_planning

import com.example.finance_planning.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlanningIntentTest {
    private fun intent(vararg changes: Pair<String, Any?>): JSONObject = JSONObject()
        .put("contract_version", "2.0")
        .put("plan_id", "59c827db-79fa-4a56-943d-291831f28f51")
        .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
        .put("version", 3).put("authoring_state", "APPROVED").put("execution_state", "NOT_STARTED")
        .put("environment", "production").put("recipient_uid", "firebase-user").put("account", "012345")
        .put("symbol", "FPT").put("side", "BUY").put("quantity", "100")
        .put("limit_price_vnd", "90000").put("scheduled_at", "2026-10-01T09:00:00+07:00")
        .put("window_starts_at", "2026-10-01T09:00:00+07:00")
        .put("window_ends_at", "2026-10-01T14:30:00+07:00")
        .put("source_context", JSONObject().put("source_id", "source-sheet-0001").put("source_generation", 7))
        .put("cash_requirements", JSONObject().put("currency", "VND")
            .put("principal_vnd", "9000000").put("fee_reserve_vnd", "18000")
            .put("required_cash_vnd", "9018000").put("fee_reserve_rate", "0.002")
            .put("policy_version", "cash-v1").put("cash_only", true))
        .put("eligibility", JSONObject().put("eligible", true).put("reasons", JSONArray()))
        .also { value -> changes.forEach { (key, changed) -> value.put(key, changed) } }

    @Test fun approvedTypedIntentIsExecutableAndBuildsExactPreflight() {
        val parsed = PlanningIntent.parse(intent())
        assertTrue(parsed.executable)
        assertTrue(parsed.matchesDraft("FPT", "BUY", 100, 90000))
        val request = PlanningContract.preflightRequest(parsed, "012345", Instant.parse("2026-10-01T02:10:00Z"))
        assertFalse(request.has("intent_id"))
        assertEquals(3, request.getInt("expected_version"))
        assertEquals("production", request.getString("environment"))
        assertEquals("012345", request.getString("account"))
        assertEquals(7, request.getJSONObject("source_context").getInt("source_generation"))
    }

    @Test fun tentativeAndMissingApprovalFailClosed() {
        val blocked = intent("authoring_state" to "TENTATIVE",
            "eligibility" to JSONObject().put("eligible", false).put("reasons", JSONArray().put("NOT_APPROVED")))
        val parsed = PlanningIntent.parse(blocked)
        assertFalse(parsed.executable)
        assertEquals(listOf(EligibilityReason.NOT_APPROVED), parsed.eligibility.reasons)
    }

    @Test fun legacyMalformedAndUnknownEnumsNeverBecomeTypedIntents() {
        assertNull(PlanningIntent.parseOrNull(JSONObject().put("fields", JSONObject())))
        assertNull(PlanningIntent.parseOrNull(intent("contract_version" to "1.0")))
        assertNull(PlanningIntent.parseOrNull(intent("authoring_state" to "PLANNING_ONLY")))
        assertNull(PlanningIntent.parseOrNull(intent("eligibility" to
            JSONObject().put("eligible", false).put("reasons", JSONArray().put("NEW_UNKNOWN_REASON")))))
    }

    @Test fun missingEligibilityAndInvalidWindowFailClosed() {
        assertNull(PlanningIntent.parseOrNull(intent("eligibility" to JSONObject.NULL)))
        assertNull(PlanningIntent.parseOrNull(intent("window_ends_at" to "2026-10-01T08:59:00+07:00")))
        assertNull(PlanningIntent.parseOrNull(intent("source_context" to JSONObject.NULL)))
        val zeroFee = JSONObject(intent().getJSONObject("cash_requirements").toString())
            .put("fee_reserve_vnd", "0").put("required_cash_vnd", "9000000")
        assertNull(PlanningIntent.parseOrNull(intent("cash_requirements" to zeroFee)))
    }

    @Test fun preflightRequiresIdentityVersionServerTimeAndKnownReasons() {
        val response = JSONObject().put("contract_version", "2.0")
            .put("preflight_id", "c7e13b2f-0512-4947-b7ad-d0d9902a4d62")
            .put("intent_id", "792f0b94-ad11-492b-b375-91916fa4ec68")
            .put("current_version", 4).put("server_time", "2026-10-01T09:10:00+07:00")
            .put("preflight_expires_at", "2026-10-01T09:15:00+07:00")
            .put("source_context", JSONObject(intent().getJSONObject("source_context").toString()))
            .put("cash_requirements", JSONObject(intent().getJSONObject("cash_requirements").toString()))
            .put("eligibility", JSONObject().put("eligible", false)
                .put("reasons", JSONArray().put("STALE_VERSION")))
        val parsed = PreflightAuthorization.parse(response, PlanningIntent.parse(intent()))
        assertFalse(parsed.eligibility.eligible)
        assertEquals(4, parsed.currentVersion)
        assertEquals(EligibilityReason.STALE_VERSION, parsed.eligibility.reasons.single())
        val malformed = JSONObject(response.toString()).also { it.remove("server_time") }
        assertThrows(Exception::class.java) { PreflightAuthorization.parse(malformed, PlanningIntent.parse(intent())) }
    }

    @Test fun executionPageRequiresMatchingEnvelopeSourceAndGeneration() {
        fun page(envelopeGeneration: Long, itemGeneration: Long) = JSONObject()
            .put("contract_version", "2.0")
            .put("source_context", JSONObject().put("source_id", "source-sheet-0001")
                .put("source_generation", envelopeGeneration))
            .put("items", JSONArray().put(intent("source_context" to JSONObject()
                .put("source_id", "source-sheet-0001").put("source_generation", itemGeneration))))
        assertTrue(PlanningContract.executionPageReady(page(7, 7)))
        assertFalse(PlanningContract.executionPageReady(page(8, 7)))
        assertFalse(PlanningContract.executionPageReady(JSONObject()
            .put("contract_version", "2.0").put("items", JSONArray().put(intent()))))
        assertNotEquals(PlanningContract.pageSource(page(7, 7)), PlanningContract.pageSource(page(9, 9)))
    }
}
